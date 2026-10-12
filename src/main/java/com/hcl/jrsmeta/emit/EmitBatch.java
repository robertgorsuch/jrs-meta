package com.hcl.jrsmeta.emit;

import com.hcl.jrsmeta.model.Asset;
import com.hcl.jrsmeta.model.LineageEdge;
import java.util.List;
import java.util.Map;

public record EmitBatch(String serverId, String runId, String startedAt, String mode, List<Asset> assets, List<LineageEdge> edges,
                        List<String> deletedAssetIds, List<String> warnings, Map<String, Object> stats) {}
