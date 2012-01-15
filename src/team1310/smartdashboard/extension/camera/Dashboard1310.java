package team1310.smartdashboard.extension.camera;

import com.googlecode.javacpp.Loader;
import com.googlecode.javacv.cpp.opencv_core;
import com.googlecode.javacv.cpp.opencv_core.CvContour;
import com.googlecode.javacv.cpp.opencv_core.CvMemStorage;
import com.googlecode.javacv.cpp.opencv_core.CvScalar;
import com.googlecode.javacv.cpp.opencv_core.CvSeq;
import com.googlecode.javacv.cpp.opencv_core.IplImage;
import com.googlecode.javacv.cpp.opencv_imgproc;
import edu.wpi.first.smartdashboard.gui.StaticWidget;
import edu.wpi.first.smartdashboard.properties.ColorProperty;
import edu.wpi.first.smartdashboard.properties.IntegerProperty;
import edu.wpi.first.smartdashboard.properties.Property;
import edu.wpi.first.smartdashboard.properties.StringProperty;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import team1310.smartdashboard.extension.camera.AxisCamera.CameraThread;
import team1310.smartdashboard.extension.camera.AxisCamera.ImageHandler;

public class Dashboard1310 extends StaticWidget {
    private final Object cameraLock = new Object();
    private CameraThread cameraThread;
    private BufferedImage cameraImage;
    private BufferedImage filteredCameraImage;
    static private FileWriter fstream;
    static private BufferedWriter logFile;
    
    private JTable statsTable;
    private Long cameraCaptureTime = new Long(0);
    private Long imageProcessTime = new Long(0);
    
    public final IntegerProperty width = new IntegerProperty(this, "Width", 320);
    public final IntegerProperty height = new IntegerProperty(this, "Height", 240);
    public final StringProperty host = new StringProperty(this, "Host", "10.13.10.20");
    
    public final IntegerProperty threshold = new IntegerProperty(this, "Threshold", 125);
    public final ColorProperty lightColour = new ColorProperty(this, "Light Colour", Color.ORANGE);
    public final IntegerProperty numFilters = new IntegerProperty(this, "Number of filters", 0);
    
    @Override
    public void propertyChanged(Property property) {
        if(property == width || property == height) {
            try {
                cameraThread.setResolution(width.getValue().intValue(), height.getValue().intValue());
            } catch (MalformedURLException ex) {
                Logger.getLogger(Dashboard1310.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if(property == host) {
            try {
                cameraThread.setHost(host.getValue());
            } catch (MalformedURLException ex) {
                Logger.getLogger(Dashboard1310.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    public class CameraHandler implements ImageHandler {
        public CameraHandler() {
            imageFilters = new LinkedList<ImageFilter>();
        }
        
        IplImage cameraCVImage = null;
        IplImage filteredCVImage = null;
        
        @Override
        public void handleImage(BufferedImage image, long captureTime) {
            long start;
            synchronized(cameraLock) {
                start = System.currentTimeMillis();
                filteredCVImage = IplImage.createFrom(image);
                cameraCVImage = IplImage.createFrom(image);

                int remainingFilters = numFilters.getValue().intValue() != 0 ? numFilters.getValue().intValue() : imageFilters.size();
                for(ImageFilter imageFilter : imageFilters) {
                    try {
                        filteredCVImage = imageFilter.filter(filteredCVImage, cameraCVImage);
                        //opencv_core.cvReleaseImage(filteredCVImage);
                        //filteredCVImage = newFilteredImage;
                    } catch(Exception e) {
                        log("handleImage: exception in filter " + imageFilter.getClass().getName() + " : " + e);
                        return;
                    }
                    --remainingFilters;
                    if(remainingFilters == 0)
                        break;
                }

                cameraImage = cameraCVImage.getBufferedImage();
                filteredCameraImage = filteredCVImage.getBufferedImage();
            }
            repaint();
            imageProcessTime = System.currentTimeMillis() - start;
            cameraCaptureTime = captureTime;
        }
        
        private Collection<ImageFilter> imageFilters;
        
        public void addFilter(ImageFilter imageFilter) {
            imageFilters.add(imageFilter);
        }
    }
    
    public interface ImageFilter {
        public abstract IplImage filter(IplImage inputImage, IplImage originalImage);
    }
    
    public class ColourDiffFilter implements ImageFilter {
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 3);
            
            Color colour = lightColour.getValue();
            CvScalar scalar = new CvScalar();
            scalar.red(colour.getRed());
            scalar.green(colour.getGreen());
            scalar.blue(colour.getBlue());
            opencv_core.cvAbsDiffS(inputImage, ret, scalar);
            return ret;
        }
    }
    
    public class ColourPlaneConverter implements ImageFilter {
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 3);
            opencv_imgproc.cvCvtColor(inputImage, ret, opencv_imgproc.CV_RGB2HSV);
            return ret;
        }
    }
    
    public class LuminanceExtractor implements ImageFilter {
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            opencv_core.cvSplit(inputImage, null, null, ret, null);
            return ret;
        }
    }
    
    public class ThresholdFilter implements ImageFilter {
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            opencv_imgproc.cvThreshold(inputImage, ret, threshold.getValue().intValue(), 255, opencv_imgproc.CV_THRESH_BINARY);
            return ret;
        }
    }
    
    public class ErodeFilter implements ImageFilter {
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            opencv_imgproc.cvDilate(inputImage, ret, null, 1);
            return ret;
        }
    }
    
    public class DilateFilter implements ImageFilter {
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            opencv_imgproc.cvErode(inputImage, ret, null, 1);
            return ret;
        }
    }
    
    public class SkeletonFilter implements ImageFilter {
        CvMemStorage storage = CvMemStorage.create();
        CvScalar contourColour;
        
        public SkeletonFilter() {
            contourColour  = new CvScalar();
            contourColour.green(255);
        }
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage ret = inputImage.clone();
            CvSeq contour = new CvSeq(null);
            opencv_imgproc.cvFindContours(ret, storage, contour, Loader.sizeof(CvContour.class), opencv_imgproc.CV_RETR_LIST, opencv_imgproc.CV_CHAIN_APPROX_SIMPLE);
            opencv_core.cvDrawContours(originalImage, contour, contourColour, contourColour, 1, 1, 8);
            return ret;
        }
    }
    
    static public void log(String str) {
        try {
            logFile.write(str);
            logFile.write("\r\n");
            logFile.flush();
            CvSeq seq = new CvSeq();
        } catch (IOException e) {
            Logger.getLogger(Dashboard1310.class.getName()).log(Level.SEVERE, null, e);
        }
    }
    
    @Override
    public void init() {
        try {
            fstream = new FileWriter("C:\\out.txt");
            logFile = new BufferedWriter(fstream);
            
            CameraHandler cameraHandler = new CameraHandler();
            cameraHandler.addFilter(new ColourDiffFilter());
            cameraHandler.addFilter(new ColourPlaneConverter());
            cameraHandler.addFilter(new LuminanceExtractor());
            cameraHandler.addFilter(new ThresholdFilter());
            //cameraHandler.addFilter(new ErodeFilter());
            //cameraHandler.addFilter(new DilateFilter());
            cameraHandler.addFilter(new SkeletonFilter());
            
            cameraThread = new CameraThread(new AxisCamera("10.13.10.20", 320, 240), cameraHandler);
            cameraThread.start();
            
            DefaultTableModel model = new DefaultTableModel();
            statsTable = new JTable(model);
            model.addColumn("Stat");
            model.addColumn("Value");
            model.addRow(new Object[]{"Camera Capture Time (ms)", cameraCaptureTime});
            model.addRow(new Object[]{"Image Process Time (ms)", imageProcessTime});
            statsTable.setLocation(0, 240);
            add(statsTable);
            setPreferredSize(new Dimension(640, 300));
        } catch (Exception e) {
            log("init(): " + e);
        }
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        statsTable.setValueAt(cameraCaptureTime, 0, 1);
        statsTable.setValueAt(imageProcessTime, 1, 1);
        synchronized(cameraLock) {
            if(cameraImage != null) {
                g.drawImage(cameraImage, 0, 0, null);
                int imageWidth = cameraImage.getWidth();
                if(filteredCameraImage != null) {
                    g.drawImage(filteredCameraImage, imageWidth, 0, null);
                }
            }
        }
    }
}