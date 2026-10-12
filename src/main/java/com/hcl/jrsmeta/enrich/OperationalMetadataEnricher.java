package com.hcl.jrsmeta.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.Asset;
import com.hcl.jrsmeta.model.AssetType;
import com.hcl.jrsmeta.model.Attrs;
import com.hcl.jrsmeta.parse.Descriptors;
import com.hcl.jrsmeta.parse.ParseContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Spec §6.2 operational metadata: schedules (+ email recipients, output formats) and effective permissions. */
public final class OperationalMetadataEnricher {

    public List<Asset> enrich(ParseContext ctx, List<Asset> assets, List<String> warnings) {
        List<Asset> out = new ArrayList<>(assets.size());
        for (Asset a : assets) {
            if (a.parentId() != null || a.uri() == null) { out.add(a); continue; }
            Map<String, Object> extra = Attrs.of();
            if (a.type() == AssetType.BI_REPORT) extra.put("schedules", schedules(ctx, a.uri(), warnings));
            try {
                List<Map<String, Object>> perms = new ArrayList<>();
                for (JsonNode p : ctx.repo().permissions(a.uri()))
                    perms.add(Attrs.of("recipient", Descriptors.text(p, "recipient"), "mask", p.hasNonNull("mask") ? p.get("mask").asInt() : null));
                extra.put("permissions", perms);
            } catch (IOException e) { warnings.add("permissions of " + a.uri() + " unavailable: " + e.getMessage()); }
            out.add(a.withAttributes(extra));
        }
        return out;
    }

    private List<Map<String, Object>> schedules(ParseContext ctx, String reportUri, List<String> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<JsonNode> summaries;
        try { summaries = ctx.repo().jobs(reportUri); }
        catch (IOException e) { warnings.add("jobs of " + reportUri + " unavailable: " + e.getMessage()); return out; }
        for (JsonNode s : summaries) {
            long id = s.path("id").asLong();
            Map<String, Object> m = Attrs.of("id", id, "label", Descriptors.text(s, "label"), "owner", Descriptors.text(s, "owner"),
                    "nextFireTime", Descriptors.text(s.path("state"), "nextFireTime"), "previousFireTime", Descriptors.text(s.path("state"), "previousFireTime"),
                    "state", Descriptors.text(s.path("state"), "value"));
            try {
                JsonNode full = ctx.repo().job(id);
                List<String> formats = new ArrayList<>(); full.path("outputFormats").path("outputFormat").forEach(f -> formats.add(f.asText()));
                List<String> mail = new ArrayList<>(); full.path("mailNotification").path("toAddresses").path("address").forEach(x -> mail.add(x.asText()));
                m.put("outputFormats", formats); m.put("mailTo", mail);
                m.put("repositoryFolder", Descriptors.text(full.path("repositoryDestination"), "folderURI"));
            } catch (IOException e) { warnings.add("job " + id + " of " + reportUri + " detail unavailable: " + e.getMessage()); }
            out.add(m);
        }
        return out;
    }
}
