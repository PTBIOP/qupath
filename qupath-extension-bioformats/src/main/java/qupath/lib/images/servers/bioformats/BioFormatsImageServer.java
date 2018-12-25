/*-
 * #%L
 * This file is part of a QuPath extension.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.images.servers.bioformats;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferUShort;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ome.units.UNITS;
import ome.units.quantity.Length;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.formats.ClassList;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.Memoizer;
import loci.formats.MetadataTools;
import loci.formats.gui.AWTImageTools;
import loci.formats.gui.BufferedImageReader;
import loci.formats.meta.DummyMetadata;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataStore;
import loci.formats.ome.OMEXMLMetadata;

import qupath.lib.awt.color.model.ColorModelFactory;
import qupath.lib.common.ColorTools;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.regions.RegionRequest;

/**
 * QuPath ImageServer that uses the Bio-Formats library to read image data.
 * 
 * See http://www.openmicroscopy.org/site/products/bio-formats
 * 
 * See also https://docs.openmicroscopy.org/bio-formats/5.9.0/developers/matlab-dev.html#improving-reading-performance
 * 
 * @author Pete Bankhead
 *
 */
public class BioFormatsImageServer extends AbstractTileableImageServer {
	
	private static final Logger logger = LoggerFactory.getLogger(BioFormatsImageServer.class);
	
	/**
	 * Default colors
	 */
	private static final List<Integer> DEFAULT_COLORS = Arrays.asList(
		ColorTools.makeRGB(255, 0, 0),    // Red
		ColorTools.makeRGB(0, 255, 0),    // Green
		ColorTools.makeRGB(0, 0, 255),    // Blue
		ColorTools.makeRGB(255, 255, 0),  // Yellow
		ColorTools.makeRGB(0, 255, 255),  // Cyan
		ColorTools.makeRGB(255, 0, 255),  // Magenta
		ColorTools.makeRGB(255, 255, 255) // White
		);
	
	/**
	 * Minimum tile size - smaller values will be ignored.
	 */
	private static int MIN_TILE_SIZE = 32;
	
	/**
	 * Maximum tile size - larger values will be ignored.
	 */
	private static int MAX_TILE_SIZE = 4096;
	
	/**
	 * Image names (in lower case) normally associated with 'extra' images, but probably not representing the main image in the file.
	 */
	private static List<String> extraImageNames = Arrays.asList("overview", "label", "thumbnail", "macro");
	
	/**
	 * Original metadata, populated when reading the file.
	 */
	private ImageServerMetadata originalMetadata;
	
	/**
	 * Path to the base image file - will be the same as path, unless the path encodes the name of a specific series, in which case this refers to the file without the series included
	 */
	private String filePath;
	
	/**
	 * Fix issue related to VSI images having (wrong) z-slices
	 */
	private boolean doChannelZCorrectionVSI = false;
	
	/**
	 * Representation of the different time points for a time series.
	 * TODO: Test the use of different time points, if this ever becomes a common use of QuPath.
	 */
	private double[] timePoints = null;
	
	/**
	 * A map linking an identifier (image name) to series number for 'full' images.
	 */
	private Map<String, Integer> imageMap = null;
	
	/**
	 * A map linking an identifier (image name) to series number for additional images, e.g. thumbnails or macro images.
	 */
	private Map<String, Integer> associatedImageMap = null;

	/**
	 * List representing the preferred colors to use for each channel, as packed int values.
	 */
	private List<Channel> channels = new ArrayList<>();
	
	/**
	 * Delimiter between the file path and any sub-image names
	 */
	private static String delimiter = "::";

	/**
	 * Numeric identifier for the image (there might be more than one in the file)
	 */
	private int series = 0;
	
	/**
	 * QuPath-specific options for how the image server should behave, such as using parallelization or memoization.
	 */
	private BioFormatsServerOptions options;
		
	/**
	 * Manager to help keep multithreading under control.
	 */
	private static BioFormatsReaderManager manager = new BioFormatsReaderManager();
	
	/**
	 * Try to parallelize multichannel requests (experimental!)
	 */
	private boolean parallelizeMultichannel = true;
	
	/**
	 * Per-pyramid level image widths
	 */
	private int[] levelWidths;

	/**
	 * Per-pyramid level image widths
	 */
	private int[] levelHeights;

	/**
	 * Create an ImageServer using the Bio-Formats library for a specified image path.
	 * 
	 * @param path path to the image that should be opened; this might include a sub-image using the form {@code filepath::sub-image-name}
	 * @throws FormatException
	 * @throws IOException
	 * @throws DependencyException
	 * @throws ServiceException
	 */
	public BioFormatsImageServer(final String path) throws FormatException, IOException, DependencyException, ServiceException {
		this(null, path);
	}
	
	
	/**
	 * Create an ImageServer using the Bio-Formats library for a specified image path, using a specified cache.
	 * 
	 * @param cache cache in which to store retrieved image tiles
	 * @param path path to the image that should be opened; this might include a sub-image using the form {@code filepath::sub-image-name}
	 * @throws FormatException
	 * @throws IOException
	 * @throws DependencyException
	 * @throws ServiceException
	 */
	public BioFormatsImageServer(final Map<RegionRequest, BufferedImage> cache, final String path) throws FormatException, IOException, DependencyException, ServiceException {
		this(cache, path, BioFormatsServerOptions.getInstance());
	}
	

	BioFormatsImageServer(final Map<RegionRequest, BufferedImage> cache, String path, final BioFormatsServerOptions options) throws FormatException, IOException, DependencyException, ServiceException {
		super(cache);
		
		long startTime = System.currentTimeMillis();

		this.options = options;

		// Zip files tend to be slow for Bioformats to parse... so best not try
		if (path.toLowerCase().endsWith(".zip"))
			throw new FormatException("ZIP not supported");

		// Create variables for metadata
		int width = 0, height = 0, nChannels = 1, nZSlices = 1, nTimepoints = 1, tileWidth = 0, tileHeight = 0;
		double pixelWidth = Double.NaN, pixelHeight = Double.NaN, zSpacing = Double.NaN, magnification = Double.NaN;
		TimeUnit timeUnit = null;
		
		// See if there is a series name embedded in the path
		String[] splitPath = splitFilePathAndSeriesName(path);
		filePath = splitPath[0];
		String seriesName = splitPath[1];
		
	    // Create a reader & extract the metadata
		BufferedImageReader reader = manager.getPrimaryReader(this, filePath);
		IMetadata meta = (IMetadata)reader.getMetadataStore();
		
		synchronized(reader) {
			
			// Populate the image server list if we have more than one image
			int seriesIndex = -1;
			
			// If we have more than one series, we need to construct maps of 'analyzable' & associated images
			if (reader.getSeriesCount() > 1) {
				imageMap = new LinkedHashMap<>(reader.getSeriesCount());
				associatedImageMap = new LinkedHashMap<>(reader.getSeriesCount());
				// Series in the reader API should correspond to images according to the metadata API
				if (reader.getSeriesCount() != meta.getImageCount())
					logger.error("Bio-Formats series and image counts do not match");
				
				// Loop through series to find out whether we have multiresolution images, or associated images (e.g. thumbnails)
				for (int s = 0; s < meta.getImageCount(); s++) {
					reader.setSeries(s);
					String name = meta.getImageName(s);
					if (reader.isThumbnailSeries() || (reader.getResolutionCount() == 1 && extraImageNames.contains(name.toLowerCase().trim()))) {
						associatedImageMap.put(name, s);
					}
					else {
						imageMap.put(name, s);
					}
					// Set this to be the series, if necessary
					if (seriesName != null && seriesName.equals(name)) {
						seriesIndex = s;
					}
					logger.debug("Adding {}", name);
				}
				
				// If we have just one image in the image list, then reset to none - we can't switch
				if (imageMap.size() == 1 && seriesIndex < 0) {
					seriesIndex = imageMap.values().iterator().next();
					imageMap.clear();
				} else if (imageMap.size() > 1) {
					// Set default series index, if we need to
					if (seriesIndex < 0) {
						seriesIndex = imageMap.values().iterator().next();
					}
					// If we have more than one image, ensure that we have the image name correctly encoded in the path
					path = getSubImagePath(meta.getImageName(seriesIndex));
				}
			} else {
				if (seriesIndex < 0)
					seriesIndex = 0;
				imageMap = Collections.emptyMap();
			}
			
			if (seriesIndex < 0)
				throw new RuntimeException("Unable to find any valid images within " + path);
			
			// Store the series we are actually using
			this.series = seriesIndex;
			reader.setSeries(series);
			
			// Get the format in case we need it
			String format = reader.getFormat();
			logger.debug("Reading format: {}", format);
			
		    // Try getting the magnification
		    try {
		    	String objectiveID = meta.getObjectiveSettingsID(series);
		    	int objectiveIndex = -1;
		    	int instrumentIndex = -1;
		    	int nInstruments = meta.getInstrumentCount();
		    	for (int i = 0; i < nInstruments; i++) {
			    	int nObjectives = meta.getObjectiveCount(i);
			    	for (int o = 0; 0 < nObjectives; o++) {
			    		if (objectiveID.equals(meta.getObjectiveID(i, o))) {
			    			instrumentIndex = i;
			    			objectiveIndex = o;
			    			break;
			    		}
			    	}	    		
		    	}
		    	if (instrumentIndex < 0) {
		    		logger.warn("Cannot find objective for ref {}", objectiveID);
		    	} else {
			    	Double magnificationObject = meta.getObjectiveNominalMagnification(instrumentIndex, objectiveIndex);
			    	if (magnificationObject == null) {
			    		logger.warn("Nominal objective magnification missing for {}:{}", instrumentIndex, objectiveIndex);
			    	} else
			    		magnification = magnificationObject;		    		
		    	}
		    } catch (Exception e) {
		    	logger.warn("Unable to parse magnification: {}", e.getLocalizedMessage());
		    }
		    		
			// Get the dimensions for the requested series
			// The first resolution is the highest, i.e. the largest image
			width = reader.getSizeX();
			height = reader.getSizeY();
			tileWidth = reader.getOptimalTileWidth();
			tileHeight = reader.getOptimalTileHeight();
			nChannels = reader.getSizeC();
			
			// Prepared to set channel colors
			channels.clear();
						
			nZSlices = reader.getSizeZ();
			// Workaround bug whereby VSI channels can also be replicated as z-slices
			if (options.requestChannelZCorrectionVSI() && nZSlices == nChannels && nChannels > 1 && "CellSens VSI".equals(format)) {
				doChannelZCorrectionVSI = true;
				nZSlices = 1;
			}
			nTimepoints = reader.getSizeT();
			int bpp = reader.getBitsPerPixel();
			boolean isRGB = reader.isRGB() && bpp == 8 && nChannels == 3;
			
			// Try to read the default display colors for each channel from the file
			if (isRGB) {
				channels.add(new Channel("Red", ColorTools.makeRGB(255, 0, 0)));
				channels.add(new Channel("Green", ColorTools.makeRGB(0, 255, 0)));
				channels.add(new Channel("Blue", ColorTools.makeRGB(0, 0, 255)));
			}
			else {
				for (int c = 0; c < nChannels; c++) {
					ome.xml.model.primitives.Color color = null;
					String channelName = "Channel " + (c + 1);
					try {
						channelName = meta.getChannelName(series, c);
						color = meta.getChannelColor(series, c);
					} catch (Exception e) {
						logger.warn("Unable to parse color", e);
					}
					Integer channelColor = null;
					if (color != null)
						channelColor = ColorTools.makeRGBA(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
					else {
						// Select next available default color
						channelColor = DEFAULT_COLORS.get(c % DEFAULT_COLORS.size());
						// Darken colors if we've already cycled through all our defaults
						if (c > DEFAULT_COLORS.size()) {
							int scale = c / DEFAULT_COLORS.size();
							channelColor = ColorTools.makeScaledRGB(channelColor, Math.pow(0.85, scale));
						}
					}
					channels.add(new Channel(channelName, channelColor));
				}
				// Update RGB status if needed - sometimes we might really have an RGB image, but the Bio-Formats flag doesn't show this - 
				// and we want to take advantage of the optimizations where we can
				if (nChannels == 3 && 
						bpp == 8 &&
						(channels.get(0).color != null && channels.get(0).color == ColorTools.makeRGB(255, 0, 0)) &&
						(channels.get(1).color != null && channels.get(1).color == ColorTools.makeRGB(0, 255, 0)) &&
						(channels.get(2).color != null && channels.get(2).color == ColorTools.makeRGB(0, 0, 255))
						)
					isRGB = true;
			}
			
			// Try parsing pixel sizes in micrometers
		    try {
		    	Length xSize = meta.getPixelsPhysicalSizeX(series);
		    	Length ySize = meta.getPixelsPhysicalSizeY(series);
		    	if (xSize != null && ySize != null) {
		    		pixelWidth = xSize.value(UNITS.MICROMETER).doubleValue();
		    		pixelHeight = ySize.value(UNITS.MICROMETER).doubleValue();
		    	} else {
		    		pixelWidth = Double.NaN;
		    		pixelHeight = Double.NaN;			    		
		    	}
		    	// If we have multiple z-slices, parse the spacing
				if (nZSlices > 1) {
			    	Length zSize = meta.getPixelsPhysicalSizeZ(series);
			    	if (zSize != null)
			    		zSpacing = zSize.value(UNITS.MICROMETER).doubleValue();
			    	else
			    		zSpacing = Double.NaN;
			    }
			    // TODO: Check the Bioformats TimeStamps
			    if (nTimepoints > 1) {
				    logger.warn("Time stamps read from Bioformats have not been fully verified & should not be relied upon");
				    // Here, we don't try to separate timings by z-slice & channel...
				    int lastTimepoint = -1;
				    int count = 0;
				    timePoints = new double[nTimepoints];
				    logger.debug("PLANE COUNT: " + meta.getPlaneCount(series));
				    for (int plane = 0; plane < meta.getPlaneCount(series); plane++) {
				    	int timePoint = meta.getPlaneTheT(series, plane).getValue();
				    	logger.debug("Checking " + timePoint);
				    	if (timePoint != lastTimepoint) {
				    		timePoints[count] = meta.getPlaneDeltaT(series, plane).value(UNITS.SECOND).doubleValue();
				    		logger.debug(String.format("Timepoint %d: %.3f seconds", count, timePoints[count]));
				    		lastTimepoint = timePoint;
				    		count++;
				    	}
				    }
				    timeUnit = TimeUnit.SECONDS;
			    }
		    } catch (Exception e) {
		    	logger.error("Error parsing metadata", e);
		    	pixelWidth = Double.NaN;
		    	pixelHeight = Double.NaN;
		    	zSpacing = Double.NaN;
		    	timePoints = null;
		    	timeUnit = null;
		    }
		    
			// Loop through the series & determine downsamples
			int nResolutions = reader.getResolutionCount();
			double[] downsamples = new double[nResolutions];
			levelWidths = new int[nResolutions];
			levelHeights = new int[nResolutions];
			levelWidths[0] = width;
			levelHeights[0] = height;
			downsamples[0] = 1.0;
			for (int i = 1; i < nResolutions; i++) {
				reader.setResolution(i);
				int w = reader.getSizeX();
				int h = reader.getSizeY();
				levelWidths[i] = w;
				levelHeights[i] = h;
				
				/*
				 * Determining the downsamples from VSI files has proven troublesome, but they *appear* 
				 * to always be a power of two.  So we make that assumption here until it's proven wrong...
				 */
				if ("CellSens VSI".equals(format)) {
					downsamples[i] = Math.pow(2, i);
				} else {
					downsamples[i] = estimateDownsample(width, height, w, h, i);
				}
			}
			
//			// Estimate the image size from the lowest resolution of the pyramid; if it's substantially smaller, 
//			// this implies pixels would be missing at the lowest levels, which can result in strange behavior.
//			// In this case, use the truncated image dimensions instead.
//			int width2 = (int)Math.min(width, Math.ceil(reader.getSizeX() * downsamples[nResolutions-1]));
//			int height2 = (int)Math.min(height, Math.ceil(reader.getSizeY() * downsamples[nResolutions-1]));
//			if ((width - width2 > downsamples[nResolutions-1]) || (height - height2 > downsamples[nResolutions-1])) {
//				logger.error("Original image size ({} x {}) is not compatible with the predicted size from lower pyramid levels - will adapt to {} x {} instead", width, height, width2, height2);
//				width = width2;
//				height = height2;
//			}
			
			// Set metadata
			ImageServerMetadata.Builder builder = new ImageServerMetadata.Builder(path, width, height).
					setSizeC(nChannels).
					setSizeZ(nZSlices).
					setSizeT(nTimepoints).
					setPreferredDownsamples(downsamples).
					setBitDepth(bpp).
					setRGB(isRGB).
					setPixelSizeMicrons(pixelWidth, pixelHeight).
					setZSpacingMicrons(zSpacing).
					setMagnification(magnification).
					setTimeUnit(timeUnit);
			
			// Check the tile size if it is reasonable
			if (tileWidth >= MIN_TILE_SIZE && tileWidth <= MAX_TILE_SIZE && tileHeight >= MIN_TILE_SIZE && tileHeight <= MAX_TILE_SIZE)
				builder.setPreferredTileSize(tileWidth, tileHeight);
			originalMetadata = builder.build();
		}
		
		// Bioformats can use ImageIO for JPEG decoding, and permitting the disk-based cache can slow it down... so here we turn it off
		// TODO: Document - or improve - the setting of ImageIO disk cache
		ImageIO.setUseCache(false);
		
		// No need to parallelize for single-channel images
		parallelizeMultichannel = options.requestParallelizeMultichannel();
		if (nChannels() == 1 || isRGB())
			parallelizeMultichannel = false;
		
		long endTime = System.currentTimeMillis();
		logger.debug(String.format("Initialization time: %d ms", endTime-startTime));
	}
	
	
	/**
	 * Give a path that may optionally encode a series name, split to separate the 'file' part from the 'series' part.
	 * 
	 * @param path the path, which may be of the form {@code filepath} or {@code filepath::seriesName}.
	 * @return an array where the first entry is the file path and the second is the series name; the series name may be {@code null}.
	 */
	static String[] splitFilePathAndSeriesName(final String path) {
		// See if there is a series name embedded in the path
		int index = path.indexOf(delimiter);
		String seriesName = null;
		String filePath = path;
		if (index > 0 && index < path.length()-delimiter.length() &&  !new File(path).exists()) {
			seriesName = path.substring(index+delimiter.length());
			filePath = path.substring(0, index);
		}
		return new String[] {filePath, seriesName};
	}
	
	
	@Override
	protected int getLevelWidth(int level) {
		return levelWidths[level];
	}
	
	protected int getLevelHeight(int level) {
		return levelHeights[level];
	}
	
		
	/**
	 * Returns true if the reader accepts parallel tile requests, without synchronization.
	 * 
	 * This is true if parallelization is requested, and any memoization file is less than 10 MB.
	 * The idea is that larger memoization files indicate more heavyweight readers, and these need 
	 * to be kept restricted.
	 * 
	 * @return
	 */
	public boolean willParallelize() {
		return options.requestParallelization() && getWidth() > 8192 && getHeight() > 8192 && manager.getMemoizationFileSize(this) < 1024L*1024L * 10L;
	}
	
	
	/**
	 * Get a BufferedImageReader for use by the current thread.
	 * 
	 * If willParallelize() returns false, then the global reader will be provided.
	 * 
	 * @return
	 */
	private BufferedImageReader getBufferedImageReader() {
		try {
			IFormatReader ifReader = willParallelize() ? manager.getReaderForThread(this, filePath) : manager.getPrimaryReader(this, filePath);
			return BufferedImageReader.makeBufferedImageReader(ifReader);
		} catch (Exception e) {
			logger.error("Error requesting image reader", e);
			return null;
		}
	}
	
	BufferedImageReader getPrimaryReader() throws DependencyException, ServiceException, FormatException, IOException {
		return manager.getPrimaryReader(this, this.filePath);
	}
	
	int getSeries() {
		return series;
	}
	

	@Override
	public BufferedImage readTile(TileRequest tileRequest) {

		int level = tileRequest.getLevel();
		
		int tileX = tileRequest.getTileX();
		int tileY = tileRequest.getTileY();
		int tileWidth = tileRequest.getTileWidth();
		int tileHeight = tileRequest.getTileHeight();
		int z = tileRequest.getZ();
		int t = tileRequest.getT();
		
		BufferedImage img;
		
		BufferedImageReader ipReader = getBufferedImageReader();
		if (ipReader == null) {
			logger.warn("Reader is null - was the image already closed? " + filePath);
			return null;
		}
		synchronized(ipReader) {
			try {
				ipReader.setSeries(series);
				ipReader.setResolution(level);
				
//				// Don't ensure the region coordinates are within range - force this check elsewhere
//				if (tileX + tileWidth > ipReader.getSizeX())
//					tileWidth = ipReader.getSizeX() - tileX;
//				if (tileY + tileHeight > ipReader.getSizeY())
//					tileHeight = ipReader.getSizeY() - tileY;
				
				// Check if this is non-zero
				if (tileWidth <= 0 || tileHeight <= 0) {
					logger.warn("Unable to request pixels for region with downsampled size {} x {}", tileWidth, tileHeight);
					return null;
				}

				// Single-channel & RGB images are straightforward... nothing more to do
				if (ipReader.isRGB() || nChannels() == 1) {
					// Read the image - or at least the first channel
					int ind = ipReader.getIndex(z, 0, t);
					img = null;
					try {
						img = ipReader.openImage(ind, tileX, tileY, tileWidth, tileHeight);
					} catch (Exception e) {
						logger.error("Error opening image " + ind + " for " + tileRequest.getRegionRequest(), e);
					}
					return img;
				}
				
				// If we have multiple channels, merge them
//				BufferedImage[] images = new BufferedImage[nChannels()];
				
				BufferedImage[] images = null;
				int nChannels = nChannels();
				// We can make an effort to read channels in parallel - but need to be cautious with some readers, and fall back to sequential
				if (nChannels > 1 && parallelizeMultichannel && !willParallelize()) {
					images = IntStream.range(0, nChannels).parallel().mapToObj(c -> {
						logger.trace("Requesting to parallelize channel access");
						int ind = ipReader.getIndex(z, c, t);
						BufferedImage img2;
						try {
							img2 = ipReader.openImage(ind, tileX, tileY, tileWidth, tileHeight);
							return img2;
						} catch (Exception e) {
							logger.error("Exception reading " + tileRequest.getRegionRequest() + " - turning off parallel channel reading", e);
							parallelizeMultichannel = false;
							return null;
						}
					}).toArray(n -> new BufferedImage[n]);
				}
				if (images == null)
					images = new BufferedImage[nChannels];
				for (int c = 0; c < nChannels; c++) {
					// Check if we've already read the channel previously (i.e. in parallel)
					if (images[c] != null)
						continue;
					// Read the region
					int ind;
					if (doChannelZCorrectionVSI)
						ind = ipReader.getIndex(c, 0, t);
					else
						ind = ipReader.getIndex(z, c, t);
					BufferedImage img2;
					try {
						img2 = ipReader.openImage(ind, tileX, tileY, tileWidth, tileHeight);
						images[c] = img2;
					} catch (FormatException e) {
						logger.error("Format exception reading " + tileRequest.getRegionRequest(), e);
					} catch (IOException e) {
						logger.error("IOException exception reading " + tileRequest.getRegionRequest(), e);
					}
				}
				BufferedImage imgMerged;
				if (isRGB()) { //images.length <= 4) {
					// Can use the Bio-Formats merge - but seems limited to 4 channels
					imgMerged = AWTImageTools.mergeChannels(images);
				} else {
					// Try our own merge - this makes no real effort with ColorModels, and supports only 8-bit and 16-bit unsigned
					imgMerged = mergeChannels(images, channels.stream().mapToInt(c -> c.color).toArray());
				}
				return imgMerged;

			} catch (Exception e) {
				logger.error("Error reading image region " + tileRequest.getRegionRequest()
								+ " for image size " + ipReader.getSizeX() + " x " + ipReader.getSizeY(), e);
			}
		}
		return null;
	}
	
	
	
	/**
	 * Attempt to merge 8-bit and 16-bit unsigned integer images.
	 * 
	 * @param images
	 * @return
	 */
	static BufferedImage mergeChannels(final BufferedImage images[], final int[] colors) {

		BufferedImage imgFirst = images[0];
		if (images.length == 1)
			return imgFirst;

		int w = imgFirst.getWidth();
		int h = imgFirst.getHeight();
		int type = imgFirst.getType();
		
		// If we have a custom type, try to use the transfer type
		if (type == BufferedImage.TYPE_CUSTOM) {
			int transferType = imgFirst.getRaster().getTransferType();
			switch (transferType) {
				case DataBuffer.TYPE_BYTE:
					type = BufferedImage.TYPE_BYTE_GRAY;
					break;
				case DataBuffer.TYPE_USHORT:
					type = BufferedImage.TYPE_USHORT_GRAY;
					break;
			}
		}
		
		WritableRaster raster = null;
		int[] bandIndices;
		switch (type) {
			case (BufferedImage.TYPE_BYTE_INDEXED):
				logger.debug("Merging {} images, with TYPE_BYTE_INDEXED", images.length);
			case (BufferedImage.TYPE_BYTE_GRAY):
				byte[][] bytes = new byte[images.length][];
				bandIndices = new int[images.length];
				for (int b = 0; b < images.length; b++) {
					bandIndices[b] = b;
					DataBuffer bandBuffer = images[b].getRaster().getDataBuffer();
					if (!(bandBuffer instanceof DataBufferByte))
						throw new IllegalArgumentException("Invalid DataBuffer - expected DataBufferByte, but got " + bandBuffer);
					bytes[b] = ((DataBufferByte)bandBuffer).getData();
				}
				raster = WritableRaster.createBandedRaster(
						new DataBufferByte(bytes, w*h),
						w, h, w,bandIndices, new int[images.length], null);
				return new BufferedImage(
						ColorModelFactory.createProbabilityColorModel(8, images.length, false, colors),
//						ColorModelFactory.getDummyColorModel(8*images.length),
						raster, false, null);
			case (BufferedImage.TYPE_USHORT_GRAY):
				short[][] shorts = new short[images.length][];
				bandIndices = new int[images.length];
				for (int b = 0; b < images.length; b++) {
					bandIndices[b] = b;
					DataBuffer bandBuffer = images[b].getRaster().getDataBuffer();
					if (!(bandBuffer instanceof DataBufferUShort))
						throw new IllegalArgumentException("Invalid DataBuffer - expected DataBufferUShort, but got " + bandBuffer);
					shorts[b] = ((DataBufferUShort)bandBuffer).getData();
				}
				raster = WritableRaster.createBandedRaster(
						new DataBufferUShort(shorts, w*h), w, h, w,bandIndices, new int[images.length], null);
				return new BufferedImage(
					ColorModelFactory.createProbabilityColorModel(16, images.length, false, colors),
					raster, false, null);
			case (BufferedImage.TYPE_CUSTOM):
				if (imgFirst.getRaster().getTransferType() == DataBuffer.TYPE_FLOAT) {
					BandedSampleModel sampleModel = new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, images.length);
					raster = WritableRaster.createWritableRaster(sampleModel, null);
					float[] floats = null;
					bandIndices = new int[images.length];
					for (int b = 0; b < images.length; b++) {
						bandIndices[b] = b;
						DataBuffer bandBuffer = images[b].getRaster().getDataBuffer();
						if (!(bandBuffer instanceof DataBufferFloat))
							throw new IllegalArgumentException("Invalid DataBuffer - expected DataBufferFloat, but got " + bandBuffer);
						floats = ((DataBufferFloat)bandBuffer).getData();
						raster.setSamples(0, 0, w, h, b, floats);
					}
					ColorModel colorModel = ColorModelFactory.createProbabilityColorModel(32, images.length, false, colors);
					return new BufferedImage(colorModel, raster, false, null);
				}
			default:
				throw new IllegalArgumentException("Only 8-bit or 16-bit unsigned integer images can be merged!");
		}
	}
	
	
	@Override
	public String getServerType() {
		return "Bio-Formats";
	}
	
	@Override
	public synchronized void close() throws Exception {
		super.close();
		manager.closeServer(this);
	}
	
//	@Override
//	public BufferedImage getBufferedThumbnail(int maxWidth, int maxHeight, int zPosition) {
//		BufferedImage img = super.getBufferedThumbnail(maxWidth, maxHeight, zPosition);
//		if (isRGB())
//			return img;
//		return AWTImageTools.autoscale(img);
//	}
	
	@Override
	public double getTimePoint(int ind) {
		if (nTimepoints() == 0)
			return 0;
		return timePoints[ind];
	}

	@Override
	public List<String> getSubImageList() {
		if (imageMap == null || imageMap.isEmpty())
			return Collections.emptyList();
		return new ArrayList<>(imageMap.keySet());
	}

	@Override
	public String getDisplayedImageName() {
		return getShortServerName();
	}

	@Override
	public boolean containsSubImages() {
		return imageMap != null && !imageMap.isEmpty();
	}


	@Override
	public boolean usesBaseServer(ImageServer<?> server) {
		return this == server;
	}
	
	/**
	 * Get the stored name for this channel.
	 * <p>
	 * Note that the input used base-0 indexing, although the output may be 
	 * "Channel 1", "Channel 2" etc. for more user-friendly readability.
	 * 
	 * @param channel
	 * @return
	 */
	public String getChannelName(int channel) {
		String name = channels.get(channel).name;
		if (name == null)
			return "Channel " + (channel + 1);
		return name;
	}

	MetadataStore getMetadataStore() throws DependencyException, ServiceException, FormatException, IOException {
		BufferedImageReader reader = manager.getPrimaryReader(this, filePath);
		return reader.getMetadataStore();
	}
	
	/**
	 * Retrieve a string representation of the metadata OME-XML.
	 * 
	 * @return
	 */
	public String dumpMetadata() {
		try {
			OMEXMLMetadata metadata = (OMEXMLMetadata)getMetadataStore();
			return metadata.dumpXML();
		} catch (Exception e) {
			logger.error("Unable to dump metadata", e);
		}
		return null;
	}
	
	@Override
	public Integer getDefaultChannelColor(int channel) {
		if (isRGB())
			return getDefaultRGBChannelColors(channel);
		Integer color = null;
		if (channel >= 0 && channel <= channels.size())
			 color = channels.get(channel).color;
		if (color == null)
			color = getExtendedDefaultChannelColor(channel);
		return color;
	}
	
	@Override
	public List<String> getAssociatedImageList() {
		if (associatedImageMap == null || associatedImageMap.isEmpty())
			return Collections.emptyList();
		return new ArrayList<>(associatedImageMap.keySet());
	}

	@Override
	public BufferedImage getAssociatedImage(String name) {
		if (associatedImageMap == null || !associatedImageMap.containsKey(name))
			throw new IllegalArgumentException("No associated image with name '" + name + "' for " + getPath());
		BufferedImageReader reader = getBufferedImageReader();
		synchronized (reader) {
			int series = reader.getSeries();
			try {
				reader.setSeries(associatedImageMap.get(name));
				int nResolutions = reader.getResolutionCount();
				if (nResolutions > 0) {
					reader.setResolution(0);
				}
				// TODO: Handle color transforms here, or in the display of labels/macro images - in case this isn't RGB
				BufferedImage img = reader.openImage(reader.getIndex(0, 0, 0), 0, 0, reader.getSizeX(), reader.getSizeY());
				return img;
//				return AWTImageTools.autoscale(img);
			} catch (Exception e) {
				logger.error("Error reading associated image" + name, e);
			} finally {
				reader.setSeries(series);
			}
		}
		return null;
	}
	
	
	@Override
	public File getFile() {
		File file = new File(filePath);
		if (file.exists())
			return file;
		return null;
	}


	@Override
	public String getSubImagePath(String imageName) {
		if (imageMap.containsKey(imageName))
			return filePath + delimiter + imageName;
		throw new IllegalArgumentException(toString() + " does not contain sub-image with name " + imageName);
	}


	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return originalMetadata;
	}	
	
	
	/**
	 * Helper class to manage multiple Bio-Formats image readers.
	 * 
	 * This has two purposes:
	 *  1. To allow BioFormatsImageServers reading from the same image to request pixels from the same BioFormats reader
	 *  2. To allow BioFormatsImageServers to request separate Bio-Formats image readers for different threads.
	 *  
	 * These are to address somewhat conflicting challenges.  Firstly, some readers are very memory-hungry, and 
	 * should be created as rarely as possible.  On the other side, some readers are very lightweight - and having multiple 
	 * such readers active at a time can help rapidly respond to tile requests.
	 * 
	 * It's up to any consumers to ensure that heavyweight readers aren't called for each thread.
	 */
	static class BioFormatsReaderManager {
		
		/**
		 * Map of primary readers, not associated with any thread but with metadata available.
		 */
		private Map<String, BufferedImageReader> mapPrimary = new HashMap<>();

		/**
		 * Map of paths to stored memoization file sizes.
		 */
		private Map<String, Long> mapMemoizationSizes = new HashMap<>();

		/**
		 * Map of reads for each calling thread.  Care should be taking by the calling code to ensure requests are only made for 'lightweight' readers to avoid memory problems.
		 */
		private Map<Thread, BufferedImageReader> mapReadersPerThread = new WeakHashMap<>();
		
		/**
		 * Map between active BioFormatsImageServers and Strings representing the file paths to the images involved.
		 */
		public Map<BioFormatsImageServer, String> activeServers = new WeakHashMap<>();
		
		/**
		 * Request a BufferedImageReader for a specified path that is unique for the calling thread.
		 * 
		 * Note that the state of the reader is not specified; setSeries should be called before use.
		 * 
		 * @param server
		 * @param path
		 * @return
		 * @throws DependencyException
		 * @throws ServiceException
		 * @throws FormatException
		 * @throws IOException
		 */
		public synchronized BufferedImageReader getReaderForThread(final BioFormatsImageServer server, final String path) throws DependencyException, ServiceException, FormatException, IOException {
			BufferedImageReader reader = mapReadersPerThread.get(Thread.currentThread());
			if (reader != null) {
				if (!path.equals(reader.getCurrentFile())) {
					if (reader.getCurrentFile() != null)
						reader.close();
					reader.setId(path);
				}
				return reader;
			}
//			long startTime = System.currentTimeMillis();
			reader = createReader(server.options, path, null);
//			long endTime = System.currentTimeMillis();
//			System.err.println("Initialization " + (endTime - startTime));
			mapReadersPerThread.put(Thread.currentThread(), reader);
			return reader;
		}
		
		/**
		 * Request a BufferedImageReader for the specified path.
		 * This reader will have metadata in an accessible form, but will *not* be unique for the calling thread.
		 * Therefore care needs to be taken with regard to synchronization.
		 * 
		 * Note that the state of the reader is not specified; setSeries should be called before use.
		 * 
		 * @param server
		 * @param path
		 * @return
		 * @throws DependencyException
		 * @throws ServiceException
		 * @throws FormatException
		 * @throws IOException
		 */
		public synchronized BufferedImageReader getPrimaryReader(final BioFormatsImageServer server, final String path) throws DependencyException, ServiceException, FormatException, IOException {
			// Record that we now have an active server
			activeServers.put(server, path);
			// Try to reuse an existing reader
			BufferedImageReader reader = mapPrimary.get(path);
			// Create a reader if we need to
			if (reader == null) {
				// Create OME-XML metadata store
			    IMetadata meta = MetadataTools.createOMEXMLMetadata();
				reader = createReader(server.options, path, meta);
				mapPrimary.put(path, reader);
			} else {
				// Make sure the ID is set
				if (!path.equals(reader.getCurrentFile())) {
					if (reader.getCurrentFile() != null)
						reader.close(); // Shouldn't happen...
					reader.setId(path);
				}
			}
			return reader;
		}
		
		/**
		 * Explicitly register that a server has been closed.
		 * 
		 * This prompts a refresh of the primary server map, during which unused readers are closed.
		 * 
		 * @param server
		 */
		public synchronized void closeServer(final BioFormatsImageServer server) {
			// Remove the active server
			activeServers.remove(server);
			// If this is the last active server we have for a specified path, then close all related readers
			refreshPrimaryServerMap();
		}
		
		/**
		 * Check which servers are still active, and close any readers not associated with an active server.
		 */
		void refreshPrimaryServerMap() {
			Collection<String> active = activeServers.values();
			Iterator<Entry<String, BufferedImageReader>> iterator = mapPrimary.entrySet().iterator();
			while (iterator.hasNext()) {
				if (!active.contains(iterator.next().getKey()))
					iterator.remove();
			}
		}
		
		/**
		 * Close all the readers that we have.
		 */
		public void shutdown() {
			closePrimaryReaders();
			closeReadersPerThread();
		}
		
		/**
		 * Close all the primary readers.
		 */
		public synchronized void closePrimaryReaders() {
			for (BufferedImageReader reader : mapPrimary.values()) {
				try {
					reader.close();
				} catch (IOException e) {
					logger.warn("Error closing image reader", e);
				}
			}
			mapPrimary.clear();
		}
		
		/**
		 * Close all the pre-thread readers.
		 */
		public synchronized void closeReadersPerThread() {
			for (BufferedImageReader reader : mapReadersPerThread.values()) {
				try {
					reader.close();
				} catch (IOException e) {
					logger.warn("Error closing image reader", e);
				}
			}
			mapReadersPerThread.clear();
		}
		
		
		/**
		 * Create a new BufferedImageReader, with memoization if necessary.
		 * 
		 * @param id File path for the image.
		 * @param store Optional MetadataStore; this will be set in the reader if needed.
		 * @return the BufferedImageReader
		 * @throws FormatException
		 * @throws IOException
		 */
		private BufferedImageReader createReader(final BioFormatsServerOptions options, final String id, final MetadataStore store) throws FormatException, IOException {
			return createReader(options, null, id, store);
		}
		
		private long getMemoizationFileSize(final BioFormatsImageServer server) {
			return mapMemoizationSizes.getOrDefault(server.filePath, Long.valueOf(0L));
		}
		
		/**
		 * Create a new {@code BufferedImageReader}, with memoization if necessary.
		 * 
		 * @param options 	options used to control the reader generation
		 * @param cls 		optionally specify a IFormatReader class if it is already known, to avoid a search.
		 * @param id 		file path for the image.
		 * @param store 	optional MetadataStore; this will be set in the reader if needed.
		 * @return the {@code BufferedImageReader}
		 * @throws FormatException
		 * @throws IOException
		 */
		private synchronized BufferedImageReader createReader(final BioFormatsServerOptions options, final Class<? extends IFormatReader> cls, final String id, final MetadataStore store) throws FormatException, IOException {
			IFormatReader imageReader;
			if (cls != null) {
				ClassList<IFormatReader> list = new ClassList<>(IFormatReader.class);
				list.addClass(cls);
				imageReader = new ImageReader(list);
			} else
				imageReader = new ImageReader();
			
			imageReader.setFlattenedResolutions(false);
			
			Memoizer memoizer = null;
			int memoizationTimeMillis = options.getMemoizationTimeMillis();
			if (memoizationTimeMillis >= 0) {
				String pathMemoization = options.getPathMemoization();
				if (pathMemoization != null && !pathMemoization.trim().isEmpty()) {
					File dir = new File(pathMemoization);
					if (dir.isDirectory())
						memoizer = new Memoizer(imageReader, memoizationTimeMillis, dir);
					else {
						logger.warn("Memoization directory '{}' not found - will default to image directory", pathMemoization);
						memoizer = new Memoizer(imageReader, memoizationTimeMillis);
					}
				} else
					memoizer = new Memoizer(imageReader, memoizationTimeMillis);
				imageReader = memoizer;
			}
			
			if (store != null) {
				imageReader.setMetadataStore(store);
			}
			else
				imageReader.setMetadataStore(new DummyMetadata());
			
			if (id != null) {
				if (memoizer != null) {
					File fileMemo = ((Memoizer)imageReader).getMemoFile(id);
					long memoizationFileSize = fileMemo == null ? 0L : fileMemo.length();
					boolean memoFileExists = fileMemo != null && fileMemo.exists();
					try {
						imageReader.setId(id);
						mapMemoizationSizes.put(id, memoizationFileSize);
					} catch (Exception e) {
						if (memoFileExists) {
							logger.warn("Problem with memoization file {} ({}), will delete", fileMemo.getName(), e.getLocalizedMessage());
							fileMemo.delete();
						}
						imageReader.close();
						imageReader.setId(id);
					}
					memoizationFileSize = fileMemo == null ? 0L : fileMemo.length();
					if (memoizationFileSize == 0L)
						logger.info("No memoization file generated for {}", id);
					else if (!memoFileExists)
						logger.info(String.format("Generating memoization file %s (%.2f MB)", fileMemo.getAbsolutePath(), memoizationFileSize/1024.0/1024.0));
					else
						logger.debug("Memoization file exists at {}", fileMemo.getAbsolutePath());
				} else {
					imageReader.setId(id);
				}
			}
			return BufferedImageReader.makeBufferedImageReader(imageReader);
		}
		
		
	}
	
	static class Channel {
		
		private final String name;
		private final Integer color;
		
		Channel(final String name, final Integer color) {
			this.name = name;
			this.color = color;
		}
		
	}
	
}