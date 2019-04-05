/*
 * MIT License
 *
 * Copyright (c) 2018 Bonosoft
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package photon.file.parts;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * by bn on 01/07/2018.
 */
public class PhotonFileLayer implements Cloneable {
    private float layerPositionZ;
    private float layerExposure;
    private float layerOffTimeSeconds;
    private int dataAddress;
    private int dataSize;
    private int unknown1;
    private int unknown2;
    private int unknown3;
    private int unknown4;

    private byte[] imageData;

    private byte[] packedLayerImage;

    private ArrayList<BitSet> islandRows;
    private int isLandsCount;
    private long pixels;

    private boolean extendsMargin;
    private PhotonFileHeader photonFileHeader;
    public boolean isCalculated;

    private PhotonFileLayer(PhotonInputStream ds) throws Exception {
        layerPositionZ = ds.readFloat();
        layerExposure = ds.readFloat();
        layerOffTimeSeconds = ds.readFloat();

        dataAddress = ds.readInt();
        dataSize = ds.readInt();

        unknown1 = ds.readInt();
        unknown2 = ds.readInt();
        unknown3 = ds.readInt();
        unknown4 = ds.readInt();
    }

    public int save(PhotonOutputStream os, int dataPosition) throws Exception {
        os.writeFloat(layerPositionZ);
        os.writeFloat(layerExposure);
        os.writeFloat(layerOffTimeSeconds);

        dataAddress = dataPosition;

        os.writeInt(dataAddress);
        os.writeInt(dataSize);

        os.writeInt(unknown1);
        os.writeInt(unknown2);
        os.writeInt(unknown3);
        os.writeInt(unknown4);

        return dataPosition + dataSize + 1;
    }
    
    public PhotonFileLayer(PhotonFileLayer orig) throws Exception {
    	layerPositionZ = orig.layerPositionZ;
        layerExposure = orig.layerExposure;
        layerOffTimeSeconds = orig.layerOffTimeSeconds;

        dataAddress = orig.dataAddress;
        dataSize = orig.dataSize;

        unknown1 = orig.unknown1;
        unknown2 = orig.unknown2;
        unknown3 = orig.unknown3;
        unknown4 = orig.unknown4;
        
        photonFileHeader = orig.photonFileHeader;
        
        saveLayer(orig.getLayer());
    }

    public void saveData(PhotonOutputStream os) throws Exception {
        os.write(imageData, 0, dataSize);
        os.writeByte(0);
    }

    public static int getByteSize() {
        return 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4;
    }

    public ArrayList<BitSet> unpackImage(int resolutionX) {
        pixels = 0;
        resolutionX = resolutionX - 1;
        ArrayList<BitSet> unpackedImage = new ArrayList<>();
        BitSet currentRow = new BitSet();
        unpackedImage.add(currentRow);
        int x = 0;
        for (byte rle : imageData) {
            int length = rle & 0x7F;
            boolean color = (rle & 0x80) == 0x80;
            if (color) {
                pixels += length;
            }
            int endPosition = x + (length - 1);
            int lineEnd = Integer.min(endPosition, resolutionX);
            if (color) {
                currentRow.set(x, 1 + lineEnd);
            }
            if (endPosition > resolutionX) {
                currentRow = new BitSet();
                unpackedImage.add(currentRow);
                lineEnd = endPosition - (resolutionX + 1);
                if (color) {
                    currentRow.set(0, 1 + lineEnd);
                }
            }
            x = lineEnd + 1;
            if (x > resolutionX) {
                if (unpackedImage.size() == photonFileHeader.getResolutionY()) {
				    // if we would add an extra row beyond the height of the
				    // layer data then stop to keep the data consistent
                    break;
                }
                currentRow = new BitSet();
                unpackedImage.add(currentRow);
                x = 0;
            }
        }

        return unpackedImage;
    }


    private void unknownPixels(ArrayList<BitSet> unpackedImage, PhotonLayer photonLayer) {
        photonLayer.clear();

        for (int y = 0; y < unpackedImage.size(); y++) {
            BitSet currentRow = unpackedImage.get(y);
            if (currentRow != null) {
                for (int x = 0; x < currentRow.length(); x++) {
                    if (currentRow.get(x)) {
                        photonLayer.supported(x, y);
                    }
                }
            }
        }
    }

    private void calculate(ArrayList<BitSet> unpackedImage, ArrayList<BitSet> previousUnpackedImage, PhotonLayer photonLayer, PhotonFileLayer previousLayer) {
        islandRows = new ArrayList<>();
        isLandsCount = 0;

        photonLayer.clear();

        PhotonLayer layerData = null;
        
        if (previousLayer != null) layerData = previousLayer.getLayer();

        for (int y = 0; y < unpackedImage.size(); y++) {
            BitSet currentRow = unpackedImage.get(y);
            BitSet prevRow = previousUnpackedImage != null ? previousUnpackedImage.get(y) : null;
            if (currentRow != null) {
                for (int x = 0; x < currentRow.length(); x++) {
                    if (currentRow.get(x)) {
                    	
                        if (prevRow == null || (prevRow.get(x) && (layerData.get(x, y) == PhotonLayer.SUPPORTED || layerData.get(x, y) == PhotonLayer.CONNECTED))) {
                            photonLayer.supported(x, y);
                        } else {
                            photonLayer.island(x, y);
                        }
                    }
                }
            }
        }

        photonLayer.reduce();

        isLandsCount = photonLayer.setIslands(islandRows);
    }


    public static List<PhotonFileLayer> readLayers(PhotonFileHeader photonFileHeader, byte[] file, int margin, IPhotonProgress iPhotonProgress) throws Exception {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());

        List<PhotonFileLayer> layers = new ArrayList<>();


        try (PhotonInputStream ds = new PhotonInputStream(new ByteArrayInputStream(file, photonFileHeader.getLayersDefinitionOffsetAddress(), file.length))) {
            for (int i = 0; i < photonFileHeader.getNumberOfLayers(); i++) {

                iPhotonProgress.showInfo("Reading photon file layer " + i + "/" + photonFileHeader.getNumberOfLayers());

                PhotonFileLayer layer = new PhotonFileLayer(ds);
                layer.photonFileHeader = photonFileHeader;
                layer.imageData = Arrays.copyOfRange(file, layer.dataAddress, layer.dataAddress + layer.dataSize);
                layers.add(layer);
            }
        }

        photonLayer.unLink();
        System.gc();

        return layers;
    }



    public static void calculateLayers(PhotonFileHeader photonFileHeader, List<PhotonFileLayer> layers, int margin, IPhotonProgress iPhotonProgress) throws Exception {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        ArrayList<BitSet> previousUnpackedImage = null;
        PhotonFileLayer previousLayer = null;
        int i = 0;
        for (PhotonFileLayer layer : layers) {
            ArrayList<BitSet> unpackedImage = layer.unpackImage(photonFileHeader.getResolutionX());

            iPhotonProgress.showInfo("Calculating photon file layer " + i + "/" + photonFileHeader.getNumberOfLayers());

            if (margin > 0) {
                layer.extendsMargin = layer.checkMagin(unpackedImage, margin);
            }

            layer.unknownPixels(unpackedImage, photonLayer);

            layer.calculate(unpackedImage, previousUnpackedImage, photonLayer, previousLayer);

            if (previousUnpackedImage != null) {
                previousUnpackedImage.clear();
            }
            previousUnpackedImage = unpackedImage;
            previousLayer = layer;

            layer.packedLayerImage = photonLayer.packLayerImage();
            layer.isCalculated = true;

            i++;
        }
        photonLayer.unLink();
        System.gc();
    }

    public static void calculateLayers(PhotonFileHeader photonFileHeader, List<PhotonFileLayer> layers, int margin, int layerNo) throws Exception {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        ArrayList<BitSet> previousUnpackedImage = null;
        PhotonFileLayer previousLayer = null;

        if (layerNo>0) {
            previousUnpackedImage = layers.get(layerNo-1).unpackImage(photonFileHeader.getResolutionX());
            previousLayer = layers.get(layerNo-1);
        }

        for (int i=0; i<2; i++) {
            PhotonFileLayer layer = layers.get(layerNo + i);
            ArrayList<BitSet> unpackedImage = layer.unpackImage(photonFileHeader.getResolutionX());

            if (margin > 0) {
                layer.extendsMargin = layer.checkMagin(unpackedImage, margin);
            }

            layer.unknownPixels(unpackedImage, photonLayer);

            layer.calculate(unpackedImage, previousUnpackedImage, photonLayer, previousLayer);

            if (previousUnpackedImage != null) {
                previousUnpackedImage.clear();
            }
            previousUnpackedImage = unpackedImage;
            previousLayer = layer;

            layer.packedLayerImage = photonLayer.packLayerImage();
            layer.isCalculated = true;

            i++;
        }
        photonLayer.unLink();
        System.gc();
    }

    public ArrayList<PhotonRow> getRows() {
        return PhotonLayer.getRows(packedLayerImage, photonFileHeader.getResolutionX(), isCalculated);
    }

    public ArrayList<BitSet> getIslandRows() {
        return islandRows;
    }

    public int getIsLandsCount() {
        return isLandsCount;
    }

    public long getPixels() {
        return pixels;
    }

    public float getLayerPositionZ() {
        return layerPositionZ;
    }

    public void setLayerPositionZ(float layerPositionZ) {
        this.layerPositionZ = layerPositionZ;
    }

    public float getLayerExposure() {
        return layerExposure;
    }

    public float getLayerOffTime() {
        return layerOffTimeSeconds;
    }

    public void setLayerExposure(float layerExposure) {
        this.layerExposure = layerExposure;
    }

    public void setLayerOffTimeSeconds(float layerOffTimeSeconds) {
        this.layerOffTimeSeconds = layerOffTimeSeconds;
    }

    public void unLink() {
        imageData = null;
        packedLayerImage = null;
        if (islandRows!=null) {
            islandRows.clear();
        }
        photonFileHeader = null;
    }

    public boolean doExtendMargin() {
        return extendsMargin;
    }

    private boolean checkMagin(ArrayList<BitSet> unpackedImage, int margin) {
        if (unpackedImage.size() > margin) {
            // check top margin rows
            for (int i = 0; i < margin; i++) {
                if (!unpackedImage.get(i).isEmpty()) {
                    return true;
                }
            }
            // check bottom margin rows
            for (int i = unpackedImage.size() - margin; i < unpackedImage.size(); i++) {
                if (!unpackedImage.get(i).isEmpty()) {
                    return true;
                }
            }

            for (int i = margin; i < unpackedImage.size() - margin; i++) {
                BitSet row = unpackedImage.get(i);
                int nextBit = row.nextSetBit(0);
                if (nextBit >= 0 && nextBit < margin) {
                    return true;
                }
                nextBit = row.nextSetBit(photonFileHeader.getResolutionX() - margin);
                if (nextBit > photonFileHeader.getResolutionX() - margin) {
                    return true;
                }
            }

        }
        return false;
    }

    public PhotonLayer getLayer() {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        photonLayer.unpackLayerImage(packedLayerImage);
        return photonLayer;
    }

    public void getUpdateLayer(PhotonLayer photonLayer) {
        photonLayer.unpackLayerImage(packedLayerImage);
    }

    public void updateLayerIslands(PhotonLayer photonLayer) {
        islandRows = new ArrayList<>();
        isLandsCount = photonLayer.setIslands(islandRows);
    }

    public void saveLayer(PhotonLayer photonLayer) throws Exception {
        this.packedLayerImage = photonLayer.packLayerImage();
        this.imageData = photonLayer.packImageData();
        this.dataSize = imageData.length;
        islandRows = new ArrayList<>();
        isLandsCount = photonLayer.setIslands(islandRows);
    }

    public ArrayList<BitSet> getUnknownRows() {
        return unpackImage(photonFileHeader.getResolutionX());
    }
}
