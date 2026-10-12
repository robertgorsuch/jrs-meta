package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.ParseResult;
import com.hcl.jrsmeta.model.ResourceLookup;
import java.io.IOException;
import java.util.Set;

/** One parser per JRS resource type family. Must never throw for a malformed payload it can partially read; use warnings. */
public interface DescriptorParser {
    Set<String> resourceTypes();
    ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException;
}
