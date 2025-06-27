import java.net.*;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Scanner;

public class UDPserver {
    private static final double LOSS_RATE = 0.2; // 20%丢包率
    private final int port;
    private int expectedSeq = 0;    // 期望接收的序列号(字节偏移)
    private final Random random = new Random();
    private int clientSeq = -1;

    public static void main(String[] args) throws Exception {
        System.out.println("输入监听端口");
        Scanner scanner = new Scanner(System.in);
        int port = scanner.nextInt();
        new UDPserver(port).start();
    }

    public UDPserver(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            System.out.println("[启动] 服务器监听端口: " + port);
            byte[] buffer = new byte[1024];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                PacketHeader header = parseHeader(packet.getData());

                // 连接处理
                if (header.type == 0) { // SYN
                    handleSyn(socket, packet, header);
                }
                // 数据传输
                else if (header.type == 3) { // DATA
                    handleData(socket, packet, header);
                }
            }
        }
    }

    // 处理SYN请求
    private void handleSyn(DatagramSocket socket, DatagramPacket packet, PacketHeader header)
            throws Exception {
        System.out.println("[握手] 收到SYN, seq=" + header.seqNum);
        clientSeq = header.seqNum;

        // 发送SYN-ACK
        int serverSeq = random.nextInt(10000);
        byte[] synAck = createPacket(1, serverSeq, header.seqNum + 1, new byte[0]);
        socket.send(new DatagramPacket(synAck, synAck.length,
                packet.getAddress(), packet.getPort()));

        System.out.println("[握手] 发送SYN-ACK, seq=" + serverSeq + ", ack=" + (header.seqNum + 1));

        // 等待ACK
        byte[] buffer = new byte[1024];
        DatagramPacket ackPacket = new DatagramPacket(buffer, buffer.length);
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < 3000) {
            try {
                socket.receive(ackPacket);
                PacketHeader ackHeader = parseHeader(ackPacket.getData());

                if (ackHeader.type == 2 && ackHeader.ackNum == serverSeq + 1) {
                    System.out.println("[握手] 连接建立完成");
                    expectedSeq = 0; // 重置期望序列号
                    return;
                }
            } catch (SocketTimeoutException e) {
                // 继续等待
            }
        }
        System.out.println("[错误] ACK超时，连接未完成");
    }

    // 处理数据包（实现随机丢包）
    private void handleData(DatagramSocket socket, DatagramPacket packet, PacketHeader header) throws Exception {
        // 收包时立即模拟丢包（不进入后续处理）
        if (random.nextDouble() < LOSS_RATE) {
            int packetIdx = header.seqNum / 80;
            System.out.println("[丢包] 模拟丢弃包 " + packetIdx + " (seq=" + header.seqNum + ")");
            return; // 直接丢弃，不发送ACK
        }

        // 按序到达处理
        if (header.seqNum == expectedSeq) {
            expectedSeq += header.dataLength;
            System.out.println("[接收] 包 " + (header.seqNum / 80) + " 已接收");
        }

        // 发送ACK（累积确认）—— 注意：丢包时不会执行到此
        byte[] ack = createPacket(2, 0, expectedSeq, new byte[0]);
        socket.send(new DatagramPacket(ack, ack.length, packet.getAddress(), packet.getPort()));
    }

    private byte[] createPacket(int type, int seq, int ack, byte[] data) {
        ByteBuffer buffer = ByteBuffer.allocate(12 + data.length);
        buffer.put((byte) type);
        buffer.putInt(seq);
        buffer.putInt(ack);
        buffer.putShort((short) data.length);
        buffer.put(new byte[1]); // 保留字节
        buffer.put(data);
        return buffer.array();
    }

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

    static class PacketHeader {
        byte type;
        int seqNum;
        int ackNum;
        short dataLength;
    }
}