package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MondrianSchemaParserTest {
    static final String XML = """
        <Schema name="FoodMart">
          <Dimension name="Geography">
            <Hierarchy hasAll="true" primaryKey="store_id" primaryKeyTable="store">
              <Join leftKey="region_id" rightKey="region_id"><Table name="store"/><Table name="region"/></Join>
              <Level name="Country" table="region" column="sales_country" type="String"/>
              <Level name="Store Name" table="store" column="store_name" type="String"/>
            </Hierarchy>
          </Dimension>
          <Cube name="Sales">
            <Table name="sales_fact_2006"/>
            <DimensionUsage source="Geography" name="Geography" foreignKey="store_id"/>
            <Dimension name="Product" foreignKey="product_id">
              <Hierarchy hasAll="true"><Table name="product"/><Level name="Brand" column="brand_name"/></Hierarchy>
            </Dimension>
            <Measure name="Unit Sales" column="unit_sales" aggregator="sum"/>
            <Measure name="Store Cost" column="store_cost" aggregator="sum"/>
          </Cube>
        </Schema>
        """;

    @Test void parse_cubesMeasuresLevelsAndSharedDimensions() throws Exception {
        MondrianSchema s = new MondrianSchemaParser().parse(XML.getBytes());
        assertThat(s.name()).isEqualTo("FoodMart");
        assertThat(s.cubes()).hasSize(1);
        MondrianSchema.Cube c = s.cubes().get(0);
        assertThat(c.factTable()).isEqualTo("sales_fact_2006");
        assertThat(c.measures()).containsExactly(new MondrianSchema.Measure("Unit Sales", "unit_sales", "sum"), new MondrianSchema.Measure("Store Cost", "store_cost", "sum"));
        assertThat(c.dimensions()).extracting(MondrianSchema.Dimension::name).containsExactly("Geography", "Product");
        assertThat(c.dimensions().get(0).levels()).containsExactly(new MondrianSchema.Level("Country", "region", "sales_country"), new MondrianSchema.Level("Store Name", "store", "store_name"));
        assertThat(c.dimensions().get(1).levels()).containsExactly(new MondrianSchema.Level("Brand", "product", "brand_name"));   // single-table hierarchy inherits table
    }
}
