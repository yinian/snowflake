package com.sankuai.inf.leaf.snowflake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sankuai.inf.leaf.snowflake.exception.CheckLastTimeException;
import org.apache.commons.io.FileUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.retry.RetryUntilElapsed;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.sankuai.inf.leaf.common.*;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.zookeeper.CreateMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
/**
 * 注释中单独节点两个字代表zk上的文件节点，自身节点代表该leaf应用分布式部署时的集群中的节点
 */
public class SnowflakeZookeeperHolder {
    private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeZookeeperHolder.class);
    /**
     * 保存自身的key  ip:port-000000001
     */
    private String zk_AddressNode = null;//保存自身的key  ip:port-000000001
    /**
     *本机ip:port，用于区分zk根节点下不同的节点
     */
    private String listenAddress = null;//保存自身的key ip:port
    /**
     * workID（机房id+机器id / 或者由zk分配）
     */
    private int workerID;
    private static final String PREFIX_ZK_PATH = "/snowflake/" + PropertyFactory.getProperties().getProperty("leaf.name");
    /**
     * 本地文件保存路径：操作系统缓存的临时目录+/${leaf.name}+/leafconf/{port}/workerID.properties
     */
    private static final String PROP_PATH = System.getProperty("java.io.tmpdir") + File.separator + PropertyFactory.getProperties().getProperty("leaf.name") + "/leafconf/{port}/workerID.properties";
    /**
     * 保存所有数据持久的节点
     */
    private static final String PATH_FOREVER = PREFIX_ZK_PATH + "/forever";//保存所有数据持久的节点
    /**
     * 本机ip，用于区分不同的节点
     */
    private String ip;
    /**
     * zk的端口
     */
    private String port;
    /**
     * zk的ip地址
     */
    private String connectionString;
    /**
     * 上一次更新时间
     */
    private long lastUpdateTime;
    /**
     * @param ip 本机器的ip地址
     * @param port 连接zk的端口号
     * @param connectionString zk的ip地址
     */
    public SnowflakeZookeeperHolder(String ip, String port, String connectionString) {
        this.ip = ip;
        this.port = port;
        this.listenAddress = ip + ":" + port;
        this.connectionString = connectionString;
    }
    /**
     * 初始化方法，包括：
     * 1. 创建zk客户端连接会话并启动客户端
     * 2. 检查/snowflake/${leaf.name}/forever根节点是否存在
     * 3. 不存在则创建根节点，获取zk分配的workerId，并写入本地文件
     * 4. 存在则查询到持久节点下属于自己的节点，得到zk分配的workerId，更新本地文件，校验是否时钟回拨
     * 5. 如果启动失败，就从本地文件中读取，弱依赖zk
     * @return
     */
    public boolean init() {
        try {
            // 1. 创建zk客户端连接会话并启动客户端
            CuratorFramework curator = createWithOptions(connectionString, new RetryUntilElapsed(1000, 4), 10000, 6000);
            // 启动客户端
            curator.start();
            // 2. 检查/snowflake/${leaf.name}/forever根节点是否存在
            Stat stat = curator.checkExists().forPath(PATH_FOREVER);
            // 注意！！！！！这一段逻辑Leaf集群中只会有一个节点执行一次，所以下面workerId不需要从zk_AddressNode中解析赋值！！！！！
            if (stat == null) {
                // 不存在根节点说明机器是第一次启动，则创建/snowflake/${leaf.name}/forever/ip:port-000000000，并写入自身节点标识和时间数据
                zk_AddressNode = createNode(curator);
                // 在本地缓存workerId，默认是0（因为此时还没有从zk获取到分配的workID，0是成员变量的默认值）
                updateLocalWorkerID(workerID);
                // 定时上报本机时间戳给/snowflake/${leaf.name}/forever根节点
                ScheduledUploadData(curator, zk_AddressNode);
                return true;
            } else {// 存在的话，说明不是第一次启动leaf应用，zk存在以前的【自身节点标识和时间数据】
                // 自身节点ip:port->0000001
                Map<String, Integer> nodeMap = Maps.newHashMap();//ip:port->00001
                // 自身节点ip:port->ip:port-000001
                Map<String, String> realNode = Maps.newHashMap();//ip:port->(ipport-000001)
                // 存在根节点，先获取根节点下所有的子节点，检查是否有属于自己的节点
                List<String> keys = curator.getChildren().forPath(PATH_FOREVER);
                for (String key : keys) {
                    String[] nodeKey = key.split("-");
                    realNode.put(nodeKey[0], key);
                    nodeMap.put(nodeKey[0], Integer.parseInt(nodeKey[1]));
                }
                // 获取zk上曾经记录的workerId，这里可以看出workerId的分配是依靠zk的自增序列号
                Integer workerid = nodeMap.get(listenAddress);
                if (workerid != null) {
                    // 有自己的节点，zk_AddressNode = /snowflake/${leaf.name}/forever+ip:port-0000001
                    zk_AddressNode = PATH_FOREVER + "/" + realNode.get(listenAddress);
                    //启动worder时使用会使用
                    workerID = workerid;
                    // 检查该节点当前的系统时间是否在最后一次上报时间之后
                    if (!checkInitTimeStamp(curator, zk_AddressNode)) {
                        // 如果不滞后，则启动失败
                        throw new CheckLastTimeException("init timestamp check error,forever node timestamp gt this node time");
                    }
                    //准备创建临时节点
                    doService(curator);
                    // 更新本地缓存的workerID
                    updateLocalWorkerID(workerID);
                    LOGGER.info("[Old NODE]find forever node have this endpoint ip-{} port-{} workid-{} childnode and start SUCCESS", ip, port, workerID);
                } else {
                    // 不存在自己的节点则表示是一个新启动的节点，则创建持久节点，不需要check时间
                    String newNode = createNode(curator);
                    zk_AddressNode = newNode;
                    String[] nodeKey = newNode.split("-");
                    // 获取到zk分配的id
                    workerID = Integer.parseInt(nodeKey[1]);
                    doService(curator);
                    updateLocalWorkerID(workerID);
                    LOGGER.info("[New NODE]can not find node on forever node that endpoint ip-{} port-{} workid-{},create own node on forever node and start SUCCESS ", ip, port, workerID);
                }
            }
        } catch (Exception e) {
            // 如果启动出错，则读取本地缓存的workerID.properties文件中的workId
            LOGGER.error("Start node ERROR {}", e);
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(new File(PROP_PATH.replace("{port}", port + ""))));
                workerID = Integer.valueOf(properties.getProperty("workerID"));
                LOGGER.warn("START FAILED ,use local node file properties workerID-{}", workerID);
            } catch (Exception e1) {
                LOGGER.error("Read file error ", e1);
                return false;
            }
        }
        return true;
    }
    /**
     * 定时上报时间
     * @param curator
     */
    private void doService(CuratorFramework curator) {
        ScheduledUploadData(curator, zk_AddressNode);// /snowflake_forever/ip:port-000000001
    }
    /**
     * 定时每3s上传数据到zookeeper，其实是指每3s上报本地时间戳
     * @param curator curator客户端
     * @param zk_AddressNode zk的节点的路径地址
     */
    private void ScheduledUploadData(final CuratorFramework curator, final String zk_AddressNode) {
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "schedule-upload-time");
                thread.setDaemon(true);
                return thread;
            }
        }).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                updateNewData(curator, zk_AddressNode);
            }
        }, 1L, 3L, TimeUnit.SECONDS);//每3s上报数据

    }
    /**
     * 检查该节点当前的系统时间是否在最后一次上报时间之后
     * @param curator
     * @param zk_AddressNode
     * @return
     * @throws Exception
     */
    private boolean checkInitTimeStamp(CuratorFramework curator, String zk_AddressNode) throws Exception {
        byte[] bytes = curator.getData().forPath(zk_AddressNode);
        Endpoint endPoint = deBuildData(new String(bytes));
        //该节点的时间不能小于最后一次上报的时间
        return !(endPoint.getTimestamp() > System.currentTimeMillis());
    }

    /**
     * 在根节点下创建持久顺序节点（/自身ip:port-00001），并把节点数据（ip+端口+当前上传的时间戳）放入value
     * @param curator
     * @return
     * @throws Exception
     */
    private String createNode(CuratorFramework curator) throws Exception {
        try {
            // creatingParentsIfNeeded()方法的意思是如果父节点不存在，则在创建节点的同时创建父节点
            return curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(PATH_FOREVER + "/" + listenAddress + "-", buildData().getBytes());
        } catch (Exception e) {
            LOGGER.error("create node error msg {} ", e.getMessage());
            throw e;
        }
    }
    /**
     * 更新zk path下的数据（上报zk本地的时间戳）
     * @param curator
     * @param path
     */
    private void updateNewData(CuratorFramework curator, String path) {
        try {
            // 如果系统时间比上一次上报的时间要早，则放弃上报时间
            if (System.currentTimeMillis() < lastUpdateTime) {
                return;
            }
            // 主要是更新zk本地当前的时间戳信息
            curator.setData().forPath(path, buildData().getBytes());
            // 记录本次上报的时间
            lastUpdateTime = System.currentTimeMillis();
        } catch (Exception e) {
            LOGGER.info("update init data error path is {} error is {}", path, e);
        }
    }

    /**
     * 构建自身节点需要上传的数据，包括ip+端口+当前上传的时间戳
     * @return
     */
    private String buildData() throws JsonProcessingException {
        Endpoint endpoint = new Endpoint(ip, port, System.currentTimeMillis());
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(endpoint);
        return json;
    }

    private Endpoint deBuildData(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Endpoint endpoint = mapper.readValue(json, Endpoint.class);
        return endpoint;
    }

    /**
     * 在自身节点的本地文件系统上缓存一个workId值，用于当zk失效，机器重启时保证仍然能够正常启动
     * @param workerID
     */
    private void updateLocalWorkerID(int workerID) {
        // 本地操作系统缓存的workerID.properties文件是否存在
        File leafConfFile = new File(PROP_PATH.replace("{port}", port));
        boolean exists = leafConfFile.exists();
        // 如果存在，则重新覆盖写入新的workerID
        LOGGER.info("file exists status is {}", exists);
        if (exists) {
            try {
                FileUtils.writeStringToFile(leafConfFile, "workerID=" + workerID, false);
                LOGGER.info("update file cache workerID is {}", workerID);
            } catch (IOException e) {
                LOGGER.error("update file cache error ", e);
            }
        } else {
            // 不存在文件说明是第一次启动zk，/snowflake/${leaf.name}/forever根节点肯定也不存在，所以需要新建workerID.properties
            try {
                boolean mkdirs = leafConfFile.getParentFile().mkdirs();
                LOGGER.info("init local file cache create parent dis status is {}, worker id is {}", mkdirs, workerID);
                if (mkdirs) {
                    if (leafConfFile.createNewFile()) {
                        FileUtils.writeStringToFile(leafConfFile, "workerID=" + workerID, false);
                        LOGGER.info("local file cache workerID is {}", workerID);
                    }
                } else {
                    LOGGER.warn("create parent dir error===");
                }
            } catch (IOException e) {
                LOGGER.warn("craete workerID conf file error", e);
            }
        }
    }
    /**
     * 创建zk客户端
     * @param connectionString 服务器列表，格式host1:port1,host2:port2,...
     * @param retryPolicy 重试策略,内建有四种重试策略,也可以自行实现RetryPolicy接口
     * @param connectionTimeoutMs 连接创建超时时间，单位毫秒，默认60000ms
     * @param sessionTimeoutMs 会话超时时间，单位毫秒，默认60000ms
     * @return
     */
    private CuratorFramework createWithOptions(String connectionString, RetryPolicy retryPolicy, int connectionTimeoutMs, int sessionTimeoutMs) {
        return CuratorFrameworkFactory.builder().connectString(connectionString)
                .retryPolicy(retryPolicy)
                .connectionTimeoutMs(connectionTimeoutMs)
                .sessionTimeoutMs(sessionTimeoutMs)
                .build();
    }

    /**
     * 上报数据结构
     */
    static class Endpoint {
        private String ip;
        private String port;
        private long timestamp;

        public Endpoint() {
        }

        public Endpoint(String ip, String port, long timestamp) {
            this.ip = ip;
            this.port = port;
            this.timestamp = timestamp;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    public String getZk_AddressNode() {
        return zk_AddressNode;
    }

    public void setZk_AddressNode(String zk_AddressNode) {
        this.zk_AddressNode = zk_AddressNode;
    }

    public String getListenAddress() {
        return listenAddress;
    }

    public void setListenAddress(String listenAddress) {
        this.listenAddress = listenAddress;
    }

    public int getWorkerID() {
        return workerID;
    }

    public void setWorkerID(int workerID) {
        this.workerID = workerID;
    }

}
