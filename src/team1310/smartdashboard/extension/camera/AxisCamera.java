package team1310.smartdashboard.extension.camera;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

public final class AxisCamera
{
    String host;
    String resolution;
    volatile URL staticURL;
    volatile URL videoURL;
    volatile InputStream imageStream;
    
    public AxisCamera(String host, int width, int height) throws MalformedURLException {
        setResolution(width, height);
        setHost(host);
        buildUrl();
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
    
    public BufferedImage getStreamImage() {
        if(imageStream == null) {
            try {
                imageStream = videoURL.openStream();
            } catch(IOException e) {
                return null;
            }
        }
        
        BufferedImage img = null;
        
        try {
            int numNewlines = 0; //for use in counting the lines in the header
            String num = "";     //for use in parsing the size of the image
            
            //while the header is being read
            while (numNewlines < 3) {
                int ch = imageStream.read(); //get the next character in the stream
                
                if (ch == '\n') { //check for a newline
                    numNewlines++;
                } else if (ch >= '0' && ch <= '9') { //check to see if the character is a number
                    num += (char)ch;
                }
            }
            
            //just checking to see if it found the image header. had one instance where it didn't find it but I'm unsure why.
            if (!"".equals(num)) {
                int size = Integer.parseInt(num); //the size of the image
                byte[] bt = new byte[size];       //create a new byte array to hold the image
                
                imageStream.skip(2);                                  //skip two bytes after the header.
                while (imageStream.available() < size) {}             //wait for enough bytes to come in.
                imageStream.read(bt);                                 //put the image bytes into `bt`
                if (imageStream.available() % size > 7)               //used to make sure the stream doesn't fall too far behind.
                    img = ImageIO.read(new ByteArrayInputStream(bt)); //turn the byte array into an image and set it to `img`
                imageStream.skip(2);                                  //skip two bytes after the image data.
            }
        } catch (IOException e) {
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
                    Dashboard1310.log("CameraThread exception: " + e);
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
