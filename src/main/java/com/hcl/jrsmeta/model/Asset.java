package com.hcl.jrsmeta.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Asset(String id, String serverId, String uri, AssetType type, String label, String description,
                    String folder, String parentId, String createdAt, String updatedAt, Integer version,
                    Map<String, Object> attributes) {

    public Asset {
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static String idOf(String serverId, String uri) { return serverId + "|" + uri; }

    public static String childIdOf(String parentId, String kind, String name) { return parentId + "#" + kind + ":" + name; }

    public static String folderOf(String uri) {
        if (uri == null) return null;
        int i = uri.lastIndexOf('/');
        return i <= 0 ? "/" : uri.substring(0, i);
    }

    public static Asset of(String serverId, ResourceLookup l, AssetType type, Map<String, Object> attrs) {
        return new Asset(idOf(serverId, l.uri()), serverId, l.uri(), type, l.label(), l.description(),
                folderOf(l.uri()), null, l.creationDate(), l.updateDate(), l.version(), attrs);
    }

    public static Asset child(Asset parent, String kind, String name, AssetType type, String label, Map<String, Object> attrs) {
        return new Asset(childIdOf(parent.id(), kind, name), parent.serverId(), null, type, label, null,
                parent.folder(), parent.id(), parent.createdAt(), parent.updatedAt(), parent.version(), attrs);
    }

    public Asset withAttributes(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(attributes);
        m.putAll(extra);
        return new Asset(id, serverId, uri, type, label, description, folder, parentId, createdAt, updatedAt, version, m);
    }
}
