package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class JrxmlParserTest {
    static final String JR7 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports" name="rev" language="java">
          <parameter name="Region" class="java.lang.String"><defaultValueExpression><![CDATA["WEST"]]></defaultValueExpression></parameter>
          <query language="SQL"><![CDATA[SELECT r.region_name AS region, SUM(s.amount) AS revenue FROM sales_fact s JOIN region r ON r.region_id = s.region_id WHERE r.region_name = $P{Region} GROUP BY r.region_name]]></query>
          <field name="region" class="java.lang.String"/>
          <field name="revenue" class="java.math.BigDecimal"/>
          <variable name="total" class="java.math.BigDecimal" calculation="Sum"><expression><![CDATA[$F{revenue}]]></expression></variable>
          <subDataset name="chartData"><query language="sql"><![CDATA[SELECT 1 AS one]]></query><field name="one" class="java.lang.Integer"/></subDataset>
          <detail><band height="20">
            <element kind="subreport" x="0" y="0" width="100" height="20"><expression><![CDATA["repo:/reports/sub/detail"]]></expression></element>
          </band></detail>
        </jasperReport>
        """;

    static final String JR6 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports" name="old">
          <queryString language="sql"><![CDATA[SELECT a FROM t]]></queryString>
          <field name="a" class="java.lang.Long"/>
          <variable name="v" class="java.lang.Long" calculation="Count"><variableExpression><![CDATA[$F{a}]]></variableExpression></variable>
          <detail><band height="10"><subreport><subreportExpression><![CDATA[$P{SUBREPORT_DIR} + "child.jasper"]]></subreportExpression></subreport></band></detail>
        </jasperReport>
        """;

    static final String DOCTYPE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE jasperReport PUBLIC "//JasperReports//DTD Report Design//EN" "http://jasperreports.sourceforge.net/dtds/jasperreport.dtd">
        <jasperReport name="legacy">
          <queryString><![CDATA[SELECT 1]]></queryString>
          <field name="x" class="java.lang.Integer"/>
        </jasperReport>
        """;

    @Test void parse_jr7Format() throws Exception {
        JrxmlModel m = new JrxmlParser().parse(JR7.getBytes(StandardCharsets.UTF_8));
        assertThat(m.queryLanguage()).isEqualTo("sql");
        assertThat(m.queryText()).startsWith("SELECT r.region_name");
        assertThat(m.fields()).containsExactly(new JrxmlModel.Field("region", "java.lang.String"), new JrxmlModel.Field("revenue", "java.math.BigDecimal"));
        assertThat(m.parameters()).containsExactly(new JrxmlModel.Parameter("Region", "java.lang.String", "\"WEST\""));
        assertThat(m.variables()).containsExactly(new JrxmlModel.Variable("total", "java.math.BigDecimal", "Sum", "$F{revenue}"));
        assertThat(m.subDatasets()).hasSize(1);
        assertThat(m.subDatasets().get(0).name()).isEqualTo("chartData");
        assertThat(m.subDatasets().get(0).fields()).containsExactly(new JrxmlModel.Field("one", "java.lang.Integer"));
        assertThat(m.subreportExpressions()).containsExactly("\"repo:/reports/sub/detail\"");
    }

    @Test void parse_jr6Format() throws Exception {
        JrxmlModel m = new JrxmlParser().parse(JR6.getBytes(StandardCharsets.UTF_8));
        assertThat(m.queryText()).isEqualTo("SELECT a FROM t");
        assertThat(m.variables().get(0).expression()).isEqualTo("$F{a}");
        assertThat(m.subreportExpressions()).containsExactly("$P{SUBREPORT_DIR} + \"child.jasper\"");
    }

    @Test void parse_doctypeJrxmlParsesWithoutNetwork() throws Exception {
        JrxmlModel m = new JrxmlParser().parse(DOCTYPE.getBytes(StandardCharsets.UTF_8));   // must not fetch the DTD
        assertThat(m.queryLanguage()).isEqualTo("sql");                                      // default when absent
        assertThat(m.fields()).hasSize(1);
    }

    @Test void parse_rejectsExternalEntities() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><jasperReport><queryString>&e;</queryString></jasperReport>";
        assertThatThrownBy(() -> new JrxmlParser().parse(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(java.io.IOException.class);
    }

    @Test void parse_topLevelFieldsExcludeSubDatasetFields() throws Exception {
        JrxmlModel m = new JrxmlParser().parse(JR7.getBytes(StandardCharsets.UTF_8));
        assertThat(m.fields()).extracting(JrxmlModel.Field::name).doesNotContain("one");
    }
}
