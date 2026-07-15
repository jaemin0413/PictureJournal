package com.picturejournal.domain.media.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.domain.media.dto.internal.ExtractedExif;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 미디어 도메인의 유스케이스와 비즈니스 규칙을 수행하는 ExifMetadataExtractor 타입이다.
 */
@Component
public class ExifMetadataExtractor {

    private final ObjectMapper objectMapper;

    public ExifMetadataExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ExtractedExif extract(byte[] imageBytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(imageBytes));
            Map<String, Object> rawTags = new LinkedHashMap<>();
            for (Directory directory : metadata.getDirectories()) {
                Map<String, String> directoryTags = new LinkedHashMap<>();
                for (Tag tag : directory.getTags()) {
                    directoryTags.put(tag.getTagName(), tag.getDescription());
                }
                if (!directoryTags.isEmpty()) {
                    rawTags.put(directory.getName(), directoryTags);
                }
            }

            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            GeoLocation geoLocation = gps == null ? null : gps.getGeoLocation();
            return new ExtractedExif(
                    toJson(rawTags),
                    ifd0 == null ? null : ifd0.getString(ExifIFD0Directory.TAG_MAKE),
                    ifd0 == null ? null : ifd0.getString(ExifIFD0Directory.TAG_MODEL),
                    subIfd == null || subIfd.getDateOriginal() == null ? null : subIfd.getDateOriginal().toInstant(),
                    geoLocation == null || geoLocation.isZero() ? null : geoLocation.getLatitude(),
                    geoLocation == null || geoLocation.isZero() ? null : geoLocation.getLongitude());
        } catch (Exception exception) {
            return ExtractedExif.empty();
        }
    }

    private String toJson(Map<String, Object> rawTags) throws JsonProcessingException {
        return objectMapper.writeValueAsString(rawTags);
    }
}
