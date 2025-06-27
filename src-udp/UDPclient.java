import java.net.*;
import java.util.*;
import java.nio.ByteBuffer;

public class UDPclient {
    private static final int WINDOW_SIZE = 400; // 400字节窗口
    private static final int MAX_PACKETS = 30;   // 发送30个包
    private static final int PACKET_SIZE = 80;   // 固定包大小80字节

    private DatagramSocket socket;
    private InetAddress serverAddress;
    private int serverPort;
    private int base = 0;          // 窗口起始序列号(字节偏移)
    private int totalSent = 0;     // 总发送包数（含重传）
    private Map<Integer, Long> sendTimes = new HashMap<>();
    private List<Long> rttList = new ArrayList<>();
    private List<byte[]> packets = new ArrayList<>();
    private boolean connected = false;
    private int lastAck = -1;

    public static void main(String[] args) throws Exception {
        System.out.println("输入ip和port,空格隔开");
        Scanner scanner = new Scanner(System.in);
        String[] ary=scanner.nextLine().split("( +)");
        new UDPclient(ary[0], Integer.parseInt(ary[1])).start();
    }

    public UDPclient(String ip, int port) throws Exception {
        this.serverAddress = InetAddress.getByName(ip);
        this.serverPort = port;
        this.socket = new DatagramSocket();
        socket.setSoTimeout(100); // 设置接收超时100ms
    }

    public void start() throws Exception {
        // 1. 建立连接（三次握手）
        if (!establishConnection()) {
            System.out.println("[错误] 连接建立失败");
            return;
        }

        // 2. 生成数据包
        generatePackets();

        // 3. 发送数据（GBN协议）
        while (base < MAX_PACKETS * PACKET_SIZE) {
            sendWindow();
            if (!waitForAck()) {
                System.out.println("[超时] 重传窗口: " + base + "-" + (base + WINDOW_SIZE - 1));
                totalSent += (base + WINDOW_SIZE) / PACKET_SIZE - base / PACKET_SIZE;
            }
        }

        // 4. 打印统计结果
        printStatistics();
        socket.close();
    }

    // 完整的三次握手实现
    private boolean establishConnection() throws Exception {
        // 第一次握手：发送SYN
        int clientSeq = new Random().nextInt(10000);
        byte[] synPacket = createPacket(0, clientSeq, 0, new byte[0]);
        socket.send(new DatagramPacket(synPacket, synPacket.length, serverAddress, serverPort));
        System.out.println("[握手] 发送SYN, seq=" + clientSeq);

        // 等待第二次握手：SYN-ACK
        byte[] buffer = new byte[1024];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < 3000) {
            try {
                socket.receive(response);
                PacketHeader header = parseHeader(response.getData());

                // 验证SYN-ACK包
                if (header.type == 1 && header.ackNum == clientSeq + 1) {
                    System.out.println("[握手] 收到SYN-ACK, seq=" + header.seqNum + ", ack=" + header.ackNum);

                    // 第三次握手：发送ACK
                    byte[] ackPacket = createPacket(2, 0, header.seqNum + 1, new byte[0]);
                    socket.send(new DatagramPacket(ackPacket, ackPacket.length, serverAddress, serverPort));
                    System.out.println("[握手] 发送ACK, ack=" + (header.seqNum + 1));
                    connected = true;
                    return true;
                }
            } catch (SocketTimeoutException e) {
                // 继续等待
            }
        }
        return false; // 超时失败
    }

    private void generatePackets() {
        for (int i = 0; i < MAX_PACKETS; i++) {
            byte[] data = new byte[PACKET_SIZE];
            new Random().nextBytes(data);
            packets.add(data);
        }
        System.out.println("[准备] 生成 " + packets.size() + " 个数据包, 每包 " + PACKET_SIZE + " 字节");
    }

    // 发送当前窗口内的数据包
    private void sendWindow() throws Exception {
        int startIdx = base / PACKET_SIZE;
        int endIdx = Math.min(startIdx + (WINDOW_SIZE / PACKET_SIZE), MAX_PACKETS);

        for (int i = startIdx; i < endIdx; i++) {
            int seq = i * PACKET_SIZE;
            byte[] data = packets.get(i);
            byte[] packet = createPacket(3, seq, 0, data);
            DatagramPacket dp = new DatagramPacket(packet, packet.length, serverAddress, serverPort);
            socket.send(dp);

            sendTimes.put(seq, System.currentTimeMillis());
            totalSent++;

            int startByte = seq;
            int endByte = seq + PACKET_SIZE - 1;
            System.out.printf("[发送] 包 %d (字节 %d-%d) 已发送%n", i, startByte, endByte);
        }
    }

    // 等待ACK
    private boolean waitForAck() throws Exception {
        byte[] buffer = new byte[1024];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        long startTime = System.currentTimeMillis();
        int timeout = calculateTimeout();

        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                socket.receive(response);
                PacketHeader header = parseHeader(response.getData());

                if (header.type == 2) { // ACK包
                    // 忽略重复ACK
                    if (header.ackNum <= lastAck) continue;

                    lastAck = header.ackNum;
                    long sendTime = sendTimes.getOrDefault(header.ackNum - PACKET_SIZE, System.currentTimeMillis());
                    long rtt = System.currentTimeMillis() - sendTime;
                    rttList.add(rtt);

                    // 更新窗口
                    base = header.ackNum;
                    int packetIdx = (header.ackNum - 1) / PACKET_SIZE;
                    System.out.printf("[确认] 包 %d (字节 %d-%d) 已确认, RTT=%dms%n",
                            packetIdx, packetIdx * PACKET_SIZE, (packetIdx + 1) * PACKET_SIZE - 1, rtt);
                    return true;
                }
            } catch (SocketTimeoutException e) {
                // 继续等待
            }
        }
        return false; // 超时
    }

    // 动态计算超时时间
    private int calculateTimeout() {
        if (rttList.isEmpty()) return 300;
        long sum = 0;
        for (long rtt : rttList) sum += rtt;
        return (int) Math.max(100, 2 * sum / rttList.size());
    }

    // 生成统计报告（中文输出）
    private void printStatistics() {
        double lossRate = (1.0 - (double) MAX_PACKETS / totalSent) * 100;

        System.out.println("\n===== 传输统计 =====");
        System.out.printf("丢包率: %.2f%%%n", lossRate);
        System.out.printf("总发送包数: %d%n", totalSent);

        if (!rttList.isEmpty()) {
            long maxRTT = Collections.max(rttList);
            long minRTT = Collections.min(rttList);
            double avgRTT = rttList.stream().mapToLong(v -> v).average().orElse(0);

            double variance = 0;
            for (long rtt : rttList) {
                variance += Math.pow(rtt - avgRTT, 2);
            }
            double stdDev = Math.sqrt(variance / rttList.size());

            System.out.printf("最大RTT: %dms%n", maxRTT);
            System.out.printf("最小RTT: %dms%n", minRTT);
            System.out.printf("平均RTT: %.2fms%n", avgRTT);
            System.out.printf("RTT标准差: %.2fms%n", stdDev);
        }
    }

    // 创建协议数据包
    private byte[] createPacket(int type, int seq, int ack, byte[] data) {
        ByteBuffer buffer = ByteBuffer.allocate(12 + data.length);
        buffer.put((byte) type);      // 1字节类型
        buffer.putInt(seq);           // 4字节序列号
        buffer.putInt(ack);           // 4字节确认号
        buffer.putShort((short) data.length); // 2字节数据长度
        buffer.put(new byte[1]);      // 1字节保留
        buffer.put(data);             // 数据
        return buffer.array();
    }

    // 解析协议头部
    private PacketHeader parseHeader(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        PacketHeader header = new PacketHeader();
        header.type = buffer.get();
        header.seqNum = buffer.getInt();
        header.ackNum = buffer.getInt();
        header.dataLength = buffer.getShort();
        buffer.get(); // 跳过保留字节
        return header;
    }

    // 协议头部结构
    static class PacketHeader {
        byte type;
        int seqNum;
        int ackNum;
        short dataLength;
    }
}