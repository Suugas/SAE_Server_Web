import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;


public class Server {
    public static void main(String[] args) throws IOException {
        int port;
        if (args.length >= 2 && args[0].equals("-p")) {
            port = Integer.parseInt(args[1]);
        }else{
            port=80;
        }
        ServerSocket serv=new ServerSocket(port);
        while(true){
            Socket sock=serv.accept();
            BufferedReader br=new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String line=br.readLine();
            String file=line.split(" ")[1];
            if (file.equals("/")){
                file="/index.html";
            }
            String ct=file.substring(file.lastIndexOf('.') + 1);
            if(ct.equals("gif")||ct.equals("png")||ct.equals("jpg")||ct.equals("jpeg")){
                file="/"+file;
                ct="image/"+ct;
            } else if (ct.equals("ico")) {
                file="/images/"+file;
                ct="image/x-icon";
            } else{
                ct="text/html";
            }
            File f=new File("../../fichiers du site web-20260507"+file);
            byte[] b=Files.readAllBytes(f.toPath());
            OutputStream os = sock.getOutputStream();
            os.write("HTTP/1.1 200 OK\r\n".getBytes());
            os.write(("Content-Type: "+ct+"\r\n").getBytes());
            os.write(("Content-Length: "+b.length+"\r\n").getBytes());
            os.write("\r\n".getBytes());
            os.write(b);
            sock.close();
            br.close();
        }
    }
}