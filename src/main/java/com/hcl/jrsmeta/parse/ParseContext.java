package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.client.JrsRepository;
import com.hcl.jrsmeta.config.ConnectorConfig;

public record ParseContext(JrsRepository repo, String serverId, ConnectorConfig config) {}
