package com.savi.timezones;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.index.strtree.STRtree;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


class IndexImpl {

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final STRtree pointToTzApproxIndex;


    //???? TODO: Probably either change from Feature to Geometry or change to get zone ID out of feature.
    private static class FeatureAndId {
        private final SimpleFeature zoneGeofeature;
        private final String zoneId;

        FeatureAndId(SimpleFeature zoneGeofeature, String zoneId) {
            this.zoneGeofeature = zoneGeofeature;
            this.zoneId = zoneId;
        }

        SimpleFeature getFeature() {
            return zoneGeofeature;
        }

        String getZoneId() {
            return zoneId;
        }

    }


    /**
     *
     * @param  baseShapeFileUrl  URL to {@code .shp} file of "shapefile" (must
     *                           have sibling {@code .shx} and {@code .dbf} files)
     * @return
     * @throws IOException //???
     */
    private static STRtree buildIndex(URL baseShapeFileUrl) throws IOException {
        if (null == baseShapeFileUrl) {
            throw new IllegalArgumentException(
                    "null baseShapeFileUrl URL (should be URL to shapefile .shp file)");
        }
        try {
            // Index from geopoint to geometry/timezone ID pair _or pairs_ for
            // geometry(-ies) near geopoint (in same index bucket as geopoint).
            final STRtree geolocToTzIndex = new STRtree();

            // Get reader/loader for "shapefile" files (given .shp and sibling
            // .shx, and .dbf files):
            final Map<String, URL> dataStoreParams = new HashMap<>();
            dataStoreParams.put("url", baseShapeFileUrl);
            final DataStore dataStore = DataStoreFinder.getDataStore(dataStoreParams);
            if (null == dataStore) {
                throw new IOException("Got null DataStore for shapefile URL "
                                       + baseShapeFileUrl);
            }

            // Get subreader for the one type ("tz_world") of data in the file:
            final FeatureSource<SimpleFeatureType, SimpleFeature> source =
                    dataStore.getFeatureSource(dataStore.getTypeNames()[0]);

            // Get and index all the the geofeatures for time zones or time zone
            // portions):
            final FeatureCollection<SimpleFeatureType, SimpleFeature> tzpFeatures =
                    source.getFeatures();
            try (final FeatureIterator<SimpleFeature> iter = tzpFeatures.features()) {
                while (iter.hasNext()) {
                    final SimpleFeature tzpFeature = iter.next();

                    // Get the actual geometry and zone ID from the feature:
                    final Geometry tzpFeatGeometry =
                            (Geometry) tzpFeature.getDefaultGeometry();
                    final String timeZoneId = (String) tzpFeature.getAttribute("TZID");
                    if (null == timeZoneId) {
                        throw new IOException("No TZID attribute on timezone geofeature "
                                              + tzpFeature );
                    }

                    // Index from (rectangular) envelope of geometry (not exact
                    // geometry); to feature with exact geometry for final
                    // resolution and zone IDfor final answer.
                    geolocToTzIndex.insert(tzpFeatGeometry.getEnvelopeInternal(),
                                           new FeatureAndId(tzpFeature, timeZoneId));
                }
            }

            // Now actually build the index.
            geolocToTzIndex.build();

            return geolocToTzIndex;
        }
        catch (IOException e) { //??? Resolve exception handling (incl. re static init.)
            throw new IOException("Error in loading timezone geometries from "
                                  + baseShapeFileUrl + ": " + e, e);
        }
        catch (RuntimeException e) { //??? Resolve exception handling (incl. re static init.)
            throw new RuntimeException("Error in timezone geometries from "
                                       + baseShapeFileUrl + ": " + e, e);
        }
    }

    private static STRtree buildIndex(File shapeFilePathname) throws IOException {
        // TO-DO: Handle file as classpath resource.
        final URL shapeFileUrl = shapeFilePathname.toURI().toURL();
        return buildIndex(shapeFileUrl);
    }

    private static STRtree buildIndexFromDefaultResource() throws IOException {
        final String baseFileRelativeResourceName = "tz_world.shp"; // ???? constant
        final Class<?> thisClass = TzWhere.class;
        final URL baseFileUrl = thisClass.getResource(baseFileRelativeResourceName);
        if (null == baseFileUrl) {
            throw new FileNotFoundException( //??? Resolve exception handling (incl. re static init.)
                    "Classpath resource \"" + baseFileRelativeResourceName
                            + "\" not found (from " + thisClass.getName()
                            + ")" ); // IOException?  RuntimeException?  other?
        }
        return buildIndex(baseFileUrl);
    }



    IndexImpl() {
        try {
            pointToTzApproxIndex = buildIndexFromDefaultResource();
        }
        catch (IOException e) { //??? Resolve exception handling (incl. re static init.)
            throw new RuntimeException(e); //????
        }
    }


    String getLatLongTimeZoneId(double latitude, double longitude) {
        String result = null;

        Coordinate queryPoint = new Coordinate(longitude, latitude);

        @SuppressWarnings("unchecked") // because we put only FeatureAndId in there
        final List<FeatureAndId> approxMatches =
                pointToTzApproxIndex.query(new Envelope(queryPoint));

        Point point = geometryFactory.createPoint(queryPoint);
        for (FeatureAndId tzpFeatureAndId: approxMatches) {
            SimpleFeature feature = tzpFeatureAndId.getFeature();
            Geometry geom = (Geometry) feature.getDefaultGeometry();

            if (geom.contains(point)) {
                if (null == result) {
                    result = tzpFeatureAndId.getZoneId();
                }
                else {
                    //??? TODO: Resolve: Can two timezone geometries both think
                    // they contain the same point?  What if the point is a vertex
                    // on the boundary between two zones (and both zones use some
                    // exact same points)?
                    // If so:  Return first seen? last seen? any one? Report condition?
                    // If not:  Throw exception for unexpected case?  Log warning?  Ignore?
                }
            }
        }
        // (Note: Result can be null even if one or more approximate matches
        // were found. (It might be that none match the actual geometry.))
        return result;
    }


    private static void testTwo(STRtree index) {
        // A point in tile(?) for two xxxx in index (as of 2016-05-19).
        Coordinate query = new Coordinate(-86.764727, 37.952126);
        System.err.println("testTwo: query = " + query);
        @SuppressWarnings("unchecked")
        List<FeatureAndId> indexMatches = index.query(new Envelope(query));
        System.err.println("testTwo: indexMatches.size() = " + indexMatches.size());
        assert  2 == indexMatches.size() : indexMatches.size();

        GeometryFactory gf = new GeometryFactory();

        Point point = gf.createPoint(query);
        System.err.println("testTwo: point = " + point);
        for (FeatureAndId featRENAME: indexMatches) {
            //System.err.println("testTwo: featRENAME = " + featRENAME);
            String zoneId = featRENAME.getZoneId();
            System.err.println("testTwo: zoneId = " + zoneId);
            SimpleFeature feature = featRENAME.getFeature();
            Geometry geom = (Geometry) feature.getDefaultGeometry();
            //System.err.println("testTwo: geom = " + geom);
            if (geom.contains(point)) {
                System.out.println("testTwo: point is contained in " + zoneId);
            }
        }
    }




    private static void testOne(STRtree indexxx) {
        final Coordinate c = new Coordinate(-77.0920, 38.8044);
        System.err.println("c =" + c);
        @SuppressWarnings("unchecked")
        List<FeatureAndId> indexMatchesxx = indexxx.query(new Envelope(c));
        System.err.println("indexMatches.size() = " + indexMatchesxx.size());

        for (FeatureAndId featRENAMExx : indexMatchesxx) {
            //System.err.println("featRENAMExx =" + featRENAMExx);
            System.out.println("testOne: " + featRENAMExx.getZoneId());
        }
    }

    public static void main(String... args) throws IOException {
        STRtree indexxx = IndexImpl.buildIndexFromDefaultResource();
        testOne(indexxx);
        testTwo(indexxx);
    }
}



