import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Reversetcpserver {
    private final int port;

    public Reversetcpserver(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        ExecutorService threadPool = Executors.newCachedThreadPool();
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("服务器启动, 监听端口: " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            }
        }
    }

    private void handleClient(Socket socket) {
        try (InputStream is = socket.getInputStream();
             OutputStream os = socket.getOutputStream()) {

            // 读取Initialization报文 (类型1 + 块数N)
            byte[] initHeader = readBytes(is, 2);
            short type = ByteBuffer.wrap(initHeader).getShort();
            if (type != 1) {
                System.err.println("无效的初始化类型: " + type);
                return;
            }

            byte[] nBytes = readBytes(is, 4);
            int nBlocks = ByteBuffer.wrap(nBytes).getInt();
            System.out.printf("客户端连接: %s, 总块数: %d%n",
                    socket.getRemoteSocketAddress(), nBlocks);

            // 发送Agree响应 (类型2)
            os.write(ByteBuffer.allocate(2).putShort((short) 2).array());
            os.flush();

            for (int i = 0; i < nBlocks; i++) {
                // 读取ReverseRequest头部 (类型3 + len)
                byte[] typeByte = readBytes(is, 2);
                short reqType = ByteBuffer.wrap(typeByte).getShort();
                byte[] lenByte = readBytes(is, 4);
                int blocklen = ByteBuffer.wrap(lenByte).getInt();
                // 读取数据块
                byte[] blockdata = readBytes(is, blocklen);
                String blockdataStr = new String(blockdata);
                // 反转数据
                String reversed = new StringBuilder(new String(blockdata)).reverse().toString();
                // 发送ReverseAnswer (类型4 + 长度 + 数据)
                ByteBuffer answerBuf = ByteBuffer.allocate(2 + 4 + reversed.length());
                answerBuf.putShort((short) 4); // Type 4
                answerBuf.putInt(reversed.length());
                answerBuf.put(reversed.getBytes());
                os.write(answerBuf.array());
                os.flush();

                System.out.printf("处理块 %d/%d (长度: %d)\n", i + 1, nBlocks, blockdata.length);
            }
        } catch (IOException e) {
            System.err.println("客户端处理错误: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private byte[] readBytes(InputStream is, int n) throws IOException {
        byte[] data = new byte[n];
        int bytesRead = 0;
        while (bytesRead < n) {
            int count = is.read(data, bytesRead, n - bytesRead);
            bytesRead += count;
        }
        return data;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int port = scanner.nextInt();
        try {
            new Reversetcpserver(port).start();
        } catch (IOException e) {
            System.err.println("服务器启动失败: " + e.getMessage());
        }
    }
}