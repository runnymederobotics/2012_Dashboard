package team1310.smartdashboard.extension.camera;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import javax.imageio.ImageIO;

public final class AxisCamera
{
    String host;
    String resolution;
    URL staticURL;
    URL videoURL;
    InputStream imageStream;
    FileOutputStream logStream;
    
    private void println(String s) {
        try {
            s += "\n";
            logStream.write(s.getBytes(), 0, s.length());
            logStream.flush();
        } catch(Exception e) {
        }
    }
    
    public AxisCamera(String host, int width, int height) throws MalformedURLException {
        setResolution(width, height);
        setHost(host);
        buildUrl();
        try {
            logStream = new FileOutputStream("C:/out.txt");
        } catch(Exception e) {
        }
    }
    
    public void setResolution(int width, int height) throws MalformedURLException {
        resolution = Integer.toString(width) + "x" + Integer.toString(height);
        buildUrl();
    }
    
    public void setHost(String host) throws MalformedURLException {
        this.host = host;
        buildUrl();
    }
    
    private void buildUrl() throws MalformedURLException {
        staticURL = new URL("http://" + host + "/axis-cgi/jpg/image.cgi?resolution=" + resolution);
        videoURL = new URL("http://" + host + "/axis-cgi/mjpg/video.cgi?resolution=" + resolution);
        imageStream = null;
    }
    
    public BufferedImage getImage() throws IOException {
        return ImageIO.read(staticURL);
    }

    byte[] buffer = new byte[1024000];
    
    public BufferedImage getStreamImage() {
        while(imageStream == null) {
            try {
                println("connecting to the camera stream...");

                URLConnection con = videoURL.openConnection();
                con.setConnectTimeout(1000);
                con.setReadTimeout(1000);
                imageStream = new BufferedInputStream(con.getInputStream());

                println("connected!");
            } catch(IOException e) {
                println("getStreamImage exception while connecting: " + e);
                try {
                    Thread.sleep(1000);
                } catch(InterruptedException ie) {
                }
            }
        }
        
        BufferedImage img = null;
        
        try {
            int numNewlines = 0;
            String num = "";

            while (numNewlines < 3) {
                int ch = imageStream.read();
                if (ch == '\n') {
                    ++numNewlines;
                } else if (ch >= '0' && ch <= '9') {
                    num += (char)ch;
                }
            }

            if(num.length() == 0) {
                throw new Exception("num.length == 0");
            }

            final int size = Integer.parseInt(num);
            if(size > buffer.length) {
                throw new Exception("image too big to fit into buffer. size: " + size);
            }

            int toSkip = 2;
            while(toSkip > 0) {
                toSkip -= imageStream.skip(toSkip);
            }

            int numRead = 0;
            do {
                final int got = imageStream.read(buffer, numRead, size - numRead);
                if(got <= 0) {
                    throw new Exception("got <= 0: " + got);
                }
                numRead += got;
            } while(numRead < size);

            img = ImageIO.read(new ByteArrayInputStream(buffer));

            toSkip = 2;
            while(toSkip > 0) {
                toSkip -= imageStream.skip(toSkip);
            }

            int available = imageStream.available();
            if(available > 0) {
                println("lagging by " + available + " bytes");
            }
        } catch (Exception e) {
            println("getStreamImage exception: " + e);
            try {
                imageStream.close();
            } catch (IOException ex) {
            }
            imageStream = null;
        }
        return img;
    }
    
    public interface ImageHandler {
        public abstract void handleImage(BufferedImage image, long captureTime);
    }
    
    static public class CameraThread extends Thread {
        private final AxisCamera axisCamera;
        private final ImageHandler imageHandler;
        
        public CameraThread(AxisCamera axisCamera, ImageHandler imageHandler) {
            this.axisCamera = axisCamera;
            this.imageHandler = imageHandler;
        }

        @Override
        public void run() {
            while(true) {
                try {
                    long start = System.currentTimeMillis();
                    BufferedImage image = axisCamera.getStreamImage();
                    long captureTime = System.currentTimeMillis() - start;
                    if(image != null) {
                        imageHandler.handleImage(image, captureTime);
                    }
                } catch(Exception e) {
                }
            }
        }
        
        public void setResolution(int width, int height) throws MalformedURLException {
            axisCamera.setResolution(width, height);
        }
        
        public void setHost(String host) throws MalformedURLException {
            axisCamera.setHost(host);
        }
    }
}
