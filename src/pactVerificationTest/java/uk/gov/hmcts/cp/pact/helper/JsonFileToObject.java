package uk.gov.hmcts.cp.pact.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Objects;

public class JsonFileToObject {

    private static final Logger LOG = LoggerFactory.getLogger(JsonFileToObject.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static <T> T readJsonFromResources(String fileName, Class<T> clazz) throws Exception {
        File file;
        try{
            file = new File(Objects.requireNonNull(JsonFileToObject.class.getClassLoader().getResource(fileName)).toURI());
        } catch (Exception e) {
            LOG.atError().log("Error loading file: {}", fileName, e);
            throw e;
        }
        return mapper.readValue(file, clazz);
    }
}