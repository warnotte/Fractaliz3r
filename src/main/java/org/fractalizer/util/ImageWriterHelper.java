package org.fractalizer.util;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

/**
 * Helper class to write images (PNG/JPG) with XMP metadata.
 * Specifically designed to inject Google Photo Sphere (GPano) metadata for 360 rendering.
 */
public class ImageWriterHelper {

    private static final String GOOGLE_PANO_NAMESPACE = "http://ns.google.com/photos/1.0/panorama/";
    
    // Standard XMP packet for 360 images
    private static String createXMPPacket(int width, int height) {
        return """
               <?xpacket begin="\ufeff" id="W5M0MpCehiHzreSzNTczkc9d"?>
               <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                   <rdf:Description rdf:about="" xmlns:GPano="%s">
                     <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
                     <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
                     <GPano:FullPanoWidthPixels>%d</GPano:FullPanoWidthPixels>
                     <GPano:FullPanoHeightPixels>%d</GPano:FullPanoHeightPixels>
                     <GPano:CroppedAreaImageWidthPixels>%d</GPano:CroppedAreaImageWidthPixels>
                     <GPano:CroppedAreaImageHeightPixels>%d</GPano:CroppedAreaImageHeightPixels>
                     <GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>
                     <GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>
                   </rdf:Description>
                 </rdf:RDF>
               </x:xmpmeta>
               <?xpacket end="w"?>""".formatted(GOOGLE_PANO_NAMESPACE, width, height, width, height);
    }

    public static void writeImage(BufferedImage image, File file, boolean is360) throws IOException {
        String format = getFileExtension(file);
        if (format.equalsIgnoreCase("jpg") || format.equalsIgnoreCase("jpeg")) {
            writeJPEG(image, file, is360);
        } else {
            writePNG(image, file, is360);
        }
    }

    private static void writeJPEG(BufferedImage image, File file, boolean is360) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        
        // High quality JPEG
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.95f);
        }

        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);

        if (is360) {
            // JPEG XMP is stored in APP1 marker (0xE1)
            String format = "javax_imageio_jpeg_image_1.0";
            Node root = metadata.getAsTree(format);
            Node markerSequence = findNode(root, "markerSequence");
            if (markerSequence == null) {
                markerSequence = new IIOMetadataNode("markerSequence");
                root.appendChild(markerSequence);
            }

            // Create the XMP packet
            String xmp = createXMPPacket(image.getWidth(), image.getHeight());
            String header = "http://ns.adobe.com/xap/1.0/\0";
            byte[] xmpBytes = xmp.getBytes("UTF-8");
            byte[] headerBytes = header.getBytes("UTF-8");
            byte[] finalData = new byte[headerBytes.length + xmpBytes.length];
            System.arraycopy(headerBytes, 0, finalData, 0, headerBytes.length);
            System.arraycopy(xmpBytes, 0, finalData, headerBytes.length, xmpBytes.length);

            IIOMetadataNode unknownNode = new IIOMetadataNode("unknown");
            unknownNode.setAttribute("MarkerTag", "225");
            unknownNode.setUserObject(finalData);
            
            markerSequence.appendChild(unknownNode);
            
            try {
                metadata.setFromTree(format, root);
            } catch (Exception e) {
                System.err.println("Warning: Could not inject JPEG metadata: " + e.getMessage());
            }
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, metadata), param);
        } finally {
            writer.dispose();
        }
    }

    private static void writePNG(BufferedImage image, File file, boolean is360) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);

        if (is360) {
            // PNG XMP is stored in iTXt chunk
            String format = "javax_imageio_png_1.0";
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
            
            IIOMetadataNode itxt = (IIOMetadataNode) findNode(root, "iTXt");
            if (itxt == null) {
                itxt = new IIOMetadataNode("iTXt");
                root.appendChild(itxt);
            }

            IIOMetadataNode entry = new IIOMetadataNode("iTXtEntry");
            entry.setAttribute("keyword", "XML:com.adobe.xmp");
            entry.setAttribute("compressionFlag", "FALSE");
            entry.setAttribute("compressionMethod", "0");
            entry.setAttribute("languageTag", "");
            entry.setAttribute("translatedKeyword", "");
            entry.setAttribute("text", createXMPPacket(image.getWidth(), image.getHeight()));

            itxt.appendChild(entry);

            try {
                metadata.mergeTree(format, root);
            } catch (Exception e) {
                System.err.println("Warning: Could not inject PNG metadata: " + e.getMessage());
            }
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, metadata), param);
        } finally {
            writer.dispose();
        }
    }

    private static Node findNode(Node root, String name) {
        Node child = root.getFirstChild();
        while (child != null) {
            if (child.getNodeName().equals(name)) {
                return child;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private static String getFileExtension(File file) {
        String name = file.getName();
        int lastIndexOf = name.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return ""; 
        }
        return name.substring(lastIndexOf + 1);
    }
}