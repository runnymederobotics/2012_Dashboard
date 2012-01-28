package team1310.smartdashboard.extension.camera;

import com.googlecode.javacpp.Loader;
import com.googlecode.javacv.cpp.opencv_core;
import com.googlecode.javacv.cpp.opencv_core.CvContour;
import com.googlecode.javacv.cpp.opencv_core.CvMemStorage;
import com.googlecode.javacv.cpp.opencv_core.CvPoint;
import com.googlecode.javacv.cpp.opencv_core.CvRect;
import com.googlecode.javacv.cpp.opencv_core.CvScalar;
import com.googlecode.javacv.cpp.opencv_core.CvSeq;
import com.googlecode.javacv.cpp.opencv_core.IplImage;
import com.googlecode.javacv.cpp.opencv_imgproc;
import edu.wpi.first.smartdashboard.gui.StaticWidget;
import edu.wpi.first.smartdashboard.properties.*;
import edu.wpi.first.wpilibj.networking.NetworkTable;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
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
    
    private NetworkTable networkTable;
    private CameraInteractor cameraInteractor;
    
    private JTable statsTable;
    private Long cameraCaptureTime = new Long(0);
    private Long imageProcessTime = new Long(0);
    private Long maxImageProcessTime = new Long(0);

    private Boolean foundTarget = new Boolean(false);
    private Double targetAngle = new Double(0);
    private Double targetDistance = new Double(0);
    
    public final IntegerProperty width = new IntegerProperty(this, "Width", 320);
    public final IntegerProperty height = new IntegerProperty(this, "Height", 240);
    public final StringProperty host = new StringProperty(this, "Host", "10.13.10.20");
    
    public final IntegerProperty threshold = new IntegerProperty(this, "Threshold", 125);
    public final ColorProperty lightColour = new ColorProperty(this, "Light Colour", Color.ORANGE);
    public final IntegerProperty numFilters = new IntegerProperty(this, "Number of Filters", 0);
    
    public final IntegerProperty houghVotes = new IntegerProperty(this, "Hough Votes", 1);
    public final IntegerProperty houghMinLineLength = new IntegerProperty(this, "Hough Min Line Length", 5);
    public final IntegerProperty houghMaxLineLength = new IntegerProperty(this, "Hough Max Line Gap", 100);
    public final DoubleProperty houghRho = new DoubleProperty(this, "Hough Rho", 1);
    public final DoubleProperty houghTheta = new DoubleProperty(this, "Hough Theta", 0.01);
    public final DoubleProperty slopeThreshold = new DoubleProperty(this, "Slope Threshold", 0.1);
    public final IntegerProperty minAreaThreshold = new IntegerProperty(this, "Min Area Threshold", 20);
    public final IntegerProperty maxAreaThreshold = new IntegerProperty(this, "Max Area Threshold", 1000000);
    public final DoubleProperty cornerAngleThreshold = new DoubleProperty(this, "Corner Angle Threshold", 10);

    public class CameraInteractor {
        int sequenceNumber = 0;
        
        NetworkTable cameraNetworkTable;
        
        CameraInteractor() {
            cameraNetworkTable = NetworkTable.getTable("Camera1310");
        }
        
        public void targetFound(double xCentre, double yCentre, double objectHeight, double imageWidth, double imageHeight) {            
            final double decimalXPos = (xCentre / imageWidth - 0.5) * 2; //-1(left) to 1
            final double decimalYPos = (yCentre / imageHeight - 0.5) * 2; //-1(top) to 1

            final double aspectRatio = imageWidth / imageHeight;
            
            final double xAngleOfView = 55.0; //Found in a pdf on axis.com
            final double yAngleOfView = xAngleOfView / aspectRatio;
            final double rlTargetHeight = 45.72; //18" * 2.54 = 45.72

            final double xTheta = decimalXPos * xAngleOfView / 2;
            final double yTheta = Math.abs(decimalYPos * yAngleOfView / 2);

            final double yDistanceFromCentre = Math.abs(yCentre - imageHeight / 2);//imageHeight / 2 - yCentre;
            final double zDistanceInPixels = yDistanceFromCentre / Math.tan(Math.toRadians(yTheta));

            final double conversionFactor = rlTargetHeight / objectHeight; //cm/px

            
            final double zDistanceIRL = zDistanceInPixels * conversionFactor;
            
            foundTarget = true;
            targetAngle = xTheta;
            targetDistance = zDistanceIRL;
            
            final double targetHeight = 71; //inches
            final double cameraHeight = 43.75; //inches
            
            //(targetHeight - cameraHeight) * 230 /
            //targetDistance = (targetHeight - cameraHeight) * 230 / yDistanceFromCentre;

            cameraNetworkTable.beginTransaction();
            sequenceNumber += 1;
            cameraNetworkTable.putInt("SequenceNumber", sequenceNumber);
            cameraNetworkTable.putBoolean("FoundTarget", true);
            cameraNetworkTable.putDouble("TargetAngle", xTheta);
            cameraNetworkTable.putDouble("TargetDistance", zDistanceIRL);
            cameraNetworkTable.endTransaction();
            //cameraNetworkTable.
        }
        
        public void targetNotFound() {
            cameraNetworkTable.beginTransaction();
            sequenceNumber += 1;
            
            foundTarget = false;
            targetAngle = 0.0;
            targetDistance = 0.0;
            
            cameraNetworkTable.putInt("SequenceNumber", sequenceNumber);
            cameraNetworkTable.putBoolean("FoundTarget", false);
            cameraNetworkTable.putDouble("TargetAngle", 0.0);
            cameraNetworkTable.putDouble("TargetDistance", 0.0);
            cameraNetworkTable.endTransaction();
        }
    }
    
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
        int counter = 0;
        
        @Override
        public void handleImage(BufferedImage image, long captureTime) {
            long start;
            counter += 1;
            synchronized(cameraLock) {
                start = System.currentTimeMillis();
                filteredCVImage = IplImage.createFrom(image);
                cameraCVImage = IplImage.createFrom(image);

                int remainingFilters = numFilters.getValue().intValue() != 0 ? numFilters.getValue().intValue() : imageFilters.size();
                for(ImageFilter imageFilter : imageFilters) {
                    try {
                        filteredCVImage = imageFilter.filter(filteredCVImage, cameraCVImage);
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
            if(imageProcessTime.longValue() > maxImageProcessTime.longValue())
                maxImageProcessTime = imageProcessTime.longValue();
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
        IplImage ret;
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            if(ret == null || ret.width() != inputImage.width() || ret.height() != inputImage.height()) {
                ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 3);
            }
            
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
        IplImage ret;
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            if(ret == null || ret.width() != inputImage.width() || ret.height() != inputImage.height()) {
                ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 3);
            }
            ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 3);
            opencv_imgproc.cvCvtColor(inputImage, ret, opencv_imgproc.CV_RGB2HSV);
            return ret;
        }
    }
    
    public class LuminanceExtractor implements ImageFilter {
        IplImage ret;
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            if(ret == null || ret.width() != inputImage.width() || ret.height() != inputImage.height()) {
                ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            }

            opencv_core.cvSplit(inputImage, null, null, ret, null);
            return ret;
        }
    }
    
    public class ThresholdFilter implements ImageFilter {
        IplImage ret;
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            if(ret == null || ret.width() != inputImage.width() || ret.height() != inputImage.height()) {
                ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            }
            opencv_imgproc.cvThreshold(inputImage, ret, threshold.getValue().intValue(), 255, opencv_imgproc.CV_THRESH_BINARY_INV);
            return ret;
        }
    }
    
    public class ErodeFilter implements ImageFilter {
        IplImage ret;
        int iterations;
        
        public ErodeFilter(int iterations) {
            this.iterations = iterations;
        }
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            if(ret == null || ret.width() != inputImage.width() || ret.height() != inputImage.height()) {
                ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, iterations);
            }
            opencv_imgproc.cvErode(inputImage, ret, null, 1);
            return ret;
        }
    }
    
    public class DilateFilter implements ImageFilter {
        IplImage ret;
        int iterations;
        
        public DilateFilter(int iterations) {
            this.iterations = iterations;
        }
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            if(ret == null || ret.width() != inputImage.width() || ret.height() != inputImage.height()) {
                ret = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, iterations);
            }
            opencv_imgproc.cvDilate(inputImage, ret, null, 1);
            return ret;
        }
    }
    
    public class Vector2D {
        public double x;
        public double y;

        public Vector2D (double x, double y) {
            this.x = x;
            this.y = y;
        }
        
        public double getLength() {
            return Math.sqrt(x * x + y * y);
        }
        
        public void toUnitVector() {
            double length = getLength();
            x /= length;
            y /= length;
        }
        
        public double getAngleTo(Vector2D other) {
            double cosTheta = this.x * other.x + this.y * other.y;
            
            return Math.toDegrees(Math.acos(cosTheta));
        }
    }
    
    public class SkeletonFilter implements ImageFilter {
        CvMemStorage storage = CvMemStorage.create();
        int sequenceNumber = 0;
        
        double getAngle(CvPoint a, CvPoint b, CvPoint c) {
            Vector2D vecBA = new Vector2D(a.x() - b.x(), a.y() - b.y());
            Vector2D vecBC = new Vector2D(c.x() - b.x(), c.y() - b.y());
            
            vecBA.toUnitVector();
            vecBC.toUnitVector();
            
            return vecBA.getAngleTo(vecBC);
        }
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage copy = inputImage.clone();
            CvSeq contour = new CvSeq(null);
            opencv_imgproc.cvFindContours(copy, storage, contour, Loader.sizeof(CvContour.class), opencv_imgproc.CV_RETR_EXTERNAL, opencv_imgproc.CV_CHAIN_APPROX_SIMPLE);
            opencv_core.cvDrawContours(originalImage, contour, CvScalar.GREEN, CvScalar.GREEN, 1, 1, 8);

            CvRect highestTarget = null;
            
            for(; contour != null && !contour.isNull(); contour = contour.h_next()) {
                CvRect boundingBox = opencv_imgproc.cvBoundingRect(contour, 0);
                int area = boundingBox.height() * boundingBox.width();
                if(area < minAreaThreshold.getValue() || area > maxAreaThreshold.getValue()) {
                    continue;
                }

                CvPoint bbCentre = new CvPoint(boundingBox.x() + boundingBox.width() / 2, boundingBox.y() + boundingBox.height() / 2);
                CvPoint minXMinY = new CvPoint(0, 0);
                int distMinMin = 0;
                CvPoint minXMaxY = new CvPoint(0, 0);
                int distMinMax = 0;
                CvPoint maxXMinY = new CvPoint(0, 0);
                int distMaxMin = 0;
                CvPoint maxXMaxY = new CvPoint(0, 0);
                int distMaxMax = 0;
                
                for(int i = 0; i < contour.total(); ++i) {
                    CvPoint thisPoint = new CvPoint(opencv_core.cvGetSeqElem(contour, i));
                    int transformedX = thisPoint.x() - bbCentre.x();
                    int transformedY = thisPoint.y() - bbCentre.y();
                    int dist = transformedX * transformedX + transformedY * transformedY;

                    if(transformedX < 0) {
                        if(transformedY < 0) {
                            if(dist >= distMinMin) {
                                minXMinY = thisPoint;
                                distMinMin = dist;
                            }
                        } else {
                            if(dist >= distMinMax) {
                                minXMaxY = thisPoint;
                                distMinMax = dist;
                            }
                        }
                    } else {
                        if(transformedY < 0) {
                            if(dist >= distMaxMin) {
                                maxXMinY = thisPoint;
                                distMaxMin = dist;
                            }
                        } else {
                            if(dist >= distMaxMax) {
                                maxXMaxY = thisPoint;
                                distMaxMax = dist;
                            }
                        }
                    }
                }
                
                if(minXMinY.x() == 0 || minXMinY.y() == 0
                        || minXMaxY.x() == 0 || minXMaxY.y() == 0
                        || maxXMinY.x() == 0 || maxXMinY.y() == 0
                        || maxXMaxY.x() == 0 || maxXMaxY.y() == 0)
                    continue;
                
                double topLeftAngle = Math.abs(getAngle(minXMaxY, minXMinY, maxXMinY));
                double topRightAngle = Math.abs(getAngle(minXMinY, maxXMinY, maxXMaxY));
                double bottomLeftAngle = Math.abs(getAngle(minXMinY, minXMaxY, maxXMaxY));
                double bottomRightAngle = Math.abs(getAngle(minXMaxY, maxXMaxY, maxXMinY));
                
                if(Math.abs(topLeftAngle - 90) < cornerAngleThreshold.getValue() &&
                        Math.abs(topRightAngle - 90) < cornerAngleThreshold.getValue() &&
                        Math.abs(bottomLeftAngle - 90) < cornerAngleThreshold.getValue() &&
                        Math.abs(bottomRightAngle - 90) < cornerAngleThreshold.getValue()) {
                    
                    if(highestTarget == null || boundingBox.y() < highestTarget.y()) {
                        highestTarget = boundingBox;
                    }
                    opencv_core.cvDrawLine(originalImage, minXMinY, minXMaxY, opencv_core.CvScalar.BLUE, 2, 8, 0);
                    opencv_core.cvDrawLine(originalImage, minXMaxY, maxXMaxY, opencv_core.CvScalar.BLUE, 2, 8, 0);
                    opencv_core.cvDrawLine(originalImage, maxXMaxY, maxXMinY, opencv_core.CvScalar.BLUE, 2, 8, 0);
                    opencv_core.cvDrawLine(originalImage, maxXMinY, minXMinY, opencv_core.CvScalar.BLUE, 2, 8, 0);
                }
                
                /*float yDiff = minXMinY.y() - minXMaxY.y();
                float xDiff = minXMinY.x() - minXMaxY.x();
                float slopeLeft = xDiff > 0 ? yDiff / xDiff : 0.0f;
                yDiff = maxXMinY.y() - maxXMaxY.y();
                xDiff = minXMinY.x() - minXMaxY.x();
                float slopeRight = xDiff > 0 ? yDiff / xDiff : 0.0f;
                
                float slopeDiff = Math.abs(slopeRight - slopeLeft);
                
                if(slopeDiff < slopeThreshold.getValue()) {
                    if(highestTarget == null || boundingBox.y() < highestTarget.y()) {
                        highestTarget = boundingBox;
                    }
                    opencv_core.cvDrawLine(originalImage, minXMinY, minXMaxY, opencv_core.CvScalar.BLUE, 2, 8, 0);
                    opencv_core.cvDrawLine(originalImage, minXMaxY, maxXMaxY, opencv_core.CvScalar.BLUE, 2, 8, 0);
                    opencv_core.cvDrawLine(originalImage, maxXMaxY, maxXMinY, opencv_core.CvScalar.BLUE, 2, 8, 0);
                    opencv_core.cvDrawLine(originalImage, maxXMinY, minXMinY, opencv_core.CvScalar.BLUE, 2, 8, 0);
                }*/
                
                opencv_core.cvLine(originalImage, minXMinY, minXMinY, opencv_core.CvScalar.YELLOW, 4, 8, 0);
                opencv_core.cvLine(originalImage, minXMaxY, minXMaxY, opencv_core.CvScalar.WHITE, 4, 8, 0);
                opencv_core.cvLine(originalImage, maxXMaxY, maxXMaxY, opencv_core.CvScalar.CYAN, 4, 8, 0);
                opencv_core.cvLine(originalImage, maxXMinY, maxXMinY, opencv_core.CvScalar.GRAY, 4, 8, 0);
            }
            
            if(highestTarget != null) {
                opencv_core.cvRectangleR(originalImage, highestTarget, CvScalar.GREEN, 6, 8, 0);
                
                //Make sure we are sending the camera interactor the centre of the bounding box and not the top left corner
                cameraInteractor.targetFound(highestTarget.x() + highestTarget.width() / 2, highestTarget.y() + highestTarget.height() / 2, highestTarget.height(), originalImage.width(), originalImage.height());
            } else {
                cameraInteractor.targetNotFound();
            }
            
            return inputImage;
        }
    }
    
    public class MorphologicalSkeletonFilter implements ImageFilter {
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            IplImage skel = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            opencv_core.cvSet1D(skel, 0, opencv_core.cvScalar(0, 0, 0, 0));
            IplImage eroded = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            IplImage temp = IplImage.create(inputImage.cvSize(), opencv_core.IPL_DEPTH_8U, 1);
            
            boolean done;
            do {
                opencv_imgproc.cvErode(inputImage, eroded, null, 1);
                opencv_imgproc.cvDilate(eroded, temp, null, 1);
                opencv_core.cvSub(inputImage, temp, temp, null);
                opencv_core.cvOr(skel, temp, skel, null);
                inputImage.copyFrom(eroded.getBufferedImage());
                
                double normal = opencv_core.cvNorm(inputImage);
                done = normal == 0;
            } while(!done);

            return skel;
        }
    }
    
    public class HoughFilter implements ImageFilter {
        CvMemStorage storage = CvMemStorage.create();
        
        @Override
        public IplImage filter(IplImage inputImage, IplImage originalImage) {
            CvSeq lines = opencv_imgproc.cvHoughLines2(inputImage, storage, opencv_imgproc.CV_HOUGH_PROBABILISTIC, houghRho.getValue(), houghTheta.getValue(), houghVotes.getValue(), houghMinLineLength.getValue(), houghMaxLineLength.getValue());
            //log("hough: got " + Integer.toString(lines.total()));
            for(int i = 0; i < lines.total(); ++i) {
                CvPoint points = new CvPoint(opencv_core.cvGetSeqElem(lines, i));
                CvPoint lineStart = points.position(0);
                CvPoint lineEnd = points.position(2);
                opencv_core.cvLine(originalImage, lineStart, lineEnd, CvScalar.BLUE, 2, 8, 0);
                //log("hough: line from " + Integer.toString(lineStart.x()) + " " + Integer.toString(lineStart.y()) + " to " + Integer.toString(lineEnd.x()) + " " + Integer.toString(lineEnd.y()));
            }
            return inputImage;
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
            NetworkTable.setTeam(1310);
            networkTable = NetworkTable.getTable("1310");
            cameraInteractor = new CameraInteractor();
            
            fstream = new FileWriter("C:\\out.txt");
            logFile = new BufferedWriter(fstream);
            
            CameraHandler cameraHandler = new CameraHandler();
            cameraHandler.addFilter(new ColourDiffFilter());
            cameraHandler.addFilter(new ColourPlaneConverter());
            cameraHandler.addFilter(new LuminanceExtractor());
            cameraHandler.addFilter(new ThresholdFilter());
            //cameraHandler.addFilter(new ErodeFilter());
            //cameraHandler.addFilter(new DilateFilter());
            //cameraHandler.addFilter(new MorphologicalSkeletonFilter());
            cameraHandler.addFilter(new SkeletonFilter());
            //cameraHandler.addFilter(new MorphologicalSkeletonFilter());
            //cameraHandler.addFilter(new ErodeFilter(1));
            //cameraHandler.addFilter(new HoughFilter());
            
            cameraThread = new CameraThread(new AxisCamera("10.13.10.20", 320, 240), cameraHandler);
            cameraThread.start();
            
            DefaultTableModel model = new DefaultTableModel();
            statsTable = new JTable(model);
            model.addColumn("Stat");
            model.addColumn("Value");
            model.addRow(new Object[]{"Camera Capture Time (ms)", cameraCaptureTime});
            model.addRow(new Object[]{"Image Process Time (ms)", imageProcessTime});
            model.addRow(new Object[]{"Max Image Process Time (ms)", maxImageProcessTime});
            model.addRow(new Object[]{"Found Target", foundTarget});
            model.addRow(new Object[]{"Target Angle", targetAngle});
            model.addRow(new Object[]{"Target Distance", targetDistance});
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
        statsTable.setValueAt(maxImageProcessTime, 2, 1);
        statsTable.setValueAt(foundTarget, 3, 1);
        statsTable.setValueAt(targetAngle, 4, 1);
        statsTable.setValueAt(targetDistance, 5, 1);
        
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