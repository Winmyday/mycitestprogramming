import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

public class Reversetcpclient {
    private static List<String> generateBlocks(String words, int lmin, int lmax) {
        List<String> blocks = new ArrayList<String>();
        int total = words.length();
        int occupied = 0;
        Random rand = new Random();
        while (occupied+lmin<= total) {
            int blocksize=rand.nextInt(lmax-lmin+1)+lmin;
            blocksize=Math.min(blocksize,total-occupied);
            blocks.add(words.substring(occupied,occupied+blocksize));
            occupied+=blocksize;
        }
        return blocks;
    }
    public static void main(String[] args){
        System.out.println("请输入IP,PORT,LMin,LMax,待传输的文件路径,以空格隔开,回车表示确认.");
        int lmin,lmax;
        String words,res="";
        String[] message;
        Scanner sc = new Scanner(System.in);
        while(true) {
            message = sc.nextLine().split("( +)");
            lmin = Integer.parseInt(message[2]);
            lmax = Integer.parseInt(message[3]);
            File f = new File(message[4]);
            if (!f.isAbsolute()) {
                f = new File(System.getProperty("user.dir") ,message[4]);
            }
            if (!f.exists()) {
                System.out.println("文件不存在,请重新输入!");
            } else {
                try {
                    words=new String(Files.readAllBytes(Paths.get(message[4])));
                    if(!words.matches("\\A\\p{ASCII}*\\z")){
                        System.out.println("文件存在非ASII字符,请检查后重新输入!");
                        continue;
                    }
                    break;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        List<String> blocks = generateBlocks(words, lmin, lmax);
        try (Socket socket = new Socket(message[0],Integer.parseInt(message[1]))){
            OutputStream os = socket.getOutputStream();
            InputStream is = socket.getInputStream();
            //init发送
            System.out.println("发送Initialization报文...");
            ByteBuffer buf = ByteBuffer.allocate(2+4);
            buf.putShort((short)1);//Type
            buf.putInt(blocks.size());//N
            os.write(buf.array());
            os.flush();
            //接收agree
            System.out.println("等待Agree响应...");
            byte[] head=readByte(is,2);
            System.out.println("收到Agree响应，开始处理数据块");
            int cnt=1;
            for (String block : blocks) {
                //reverseRequest发送
                System.out.printf("发送块 %d/%d (长度: %d字节)%n",cnt, blocks.size(), block.length());
                ByteBuffer bufout = ByteBuffer.allocate(2+4+block.length());
                bufout.putShort((short)3);
                bufout.putInt(block.length());
                bufout.put(block.getBytes());
                os.write(bufout.array());
                os.flush();
                System.out.println(block);
                //reverseAnswer接收
                System.out.println("等待Answer响应...");
                byte[] ansheader=readByte(is,2);
                byte[] anslen=readByte(is,4);
                int len=ByteBuffer.wrap(anslen).getInt();
                String ansdata=new String(readByte(is,len));
                res=ansdata+res;
                System.out.println("第"+cnt+"块:"+ansdata);
                cnt++;
            }
        }catch (Exception e){
            throw new IllegalArgumentException(e.getMessage());
        }
        String resname="res.txt";
        while(new File(resname).exists()){
            resname="r"+resname;
        }
        try(FileOutputStream f=new FileOutputStream(resname)){
            f.write(res.getBytes());
            f.flush();
            System.out.println("结果已保存为(r*)res文件(存在防重名)");
        }catch (IOException e){
            throw new RuntimeException(e);
        }
    }
    public static byte[] readByte(InputStream is,int n) throws IOException {
        byte[] data = new byte[n];
        int bytesRead = 0;
        while (bytesRead < n) {
            int count = is.read(data, bytesRead, n - bytesRead);
            bytesRead += count;
        }
        return data;
    }
}