package com.hcl.jrsmeta.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.ResourceLookup;
import java.io.IOException;
import java.util.List;

/** Everything the parsers need from JRS. Implemented over REST; faked in tests. */
public interface JrsRepository {
    ServerInfo serverInfo() throws IOException;
    List<ResourceLookup> listResources(String folderUri, boolean recursive) throws IOException;
    JsonNode descriptor(String uri, String resourceType) throws IOException;
    byte[] file(String uri, String accept) throws IOException;
    byte[] exportArchive(List<String> uris) throws IOException;
    List<JsonNode> jobs(String reportUri) throws IOException;
    JsonNode job(long id) throws IOException;
    List<JsonNode> permissions(String uri) throws IOException;
}
