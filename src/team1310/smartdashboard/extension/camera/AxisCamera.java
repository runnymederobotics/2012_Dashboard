package team1310.smartdashboard.extension.camera;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import javax.imageio.ImageIO;

public final class AxisCamera
{
    String host;
    String resolution;
    volatile URL url;
    
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
        url = new URL("http://" + host + "/axis-cgi/jpg/image.cgi?resolution=" + resolution);
    }
    
    public BufferedImage getImage() throws IOException {
        return ImageIO.read(url);
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
                    BufferedImage image = axisCamera.getImage();
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
