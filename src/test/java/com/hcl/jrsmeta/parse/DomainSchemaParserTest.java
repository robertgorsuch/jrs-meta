package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class DomainSchemaParserTest {
    static final String SCHEMA = """
        <schema xmlns="http://www.jaspersoft.com/2007/SL/XMLSchema" version="1.3">
          <dataSources><jdbcDataSource id="dsFoodMart"><schemaMap><schema name="public"/></schemaMap></jdbcDataSource></dataSources>
          <itemGroups>
            <itemGroup id="AccountsSet" label="Accounts" resourceId="JoinTree_1">
              <itemGroups>
                <itemGroup id="Address" label="Address" resourceId="JoinTree_1">
                  <items><item id="city" label="City" resourceId="JoinTree_1.customer.city"/></items>
                </itemGroup>
              </itemGroups>
              <items><item id="account_num" label="Account" resourceId="JoinTree_1.customer.account_num"/></items>
            </itemGroup>
          </itemGroups>
          <items>
            <item id="store_name" label="Store" resourceId="store.store_name"/>
            <item id="usd" label="USD" resourceId="expenses_usd.as_dollars"/>
            <item id="ghost" label="Ghost" resourceId="nowhere.x"/>
          </items>
          <resources>
            <jdbcTable id="store" datasourceId="dsFoodMart" schemaAlias="public" datasourceTableName="store">
              <fieldList><field id="store_name" fieldDBName="store_name" type="java.lang.String"/></fieldList>
            </jdbcTable>
            <jdbcTable id="customer" datasourceId="dsFoodMart" schemaAlias="public" datasourceTableName="customer">
              <fieldList><field id="account_num" fieldDBName="account_num" type="java.lang.Long"/><field id="city" fieldDBName="city" type="java.lang.String"/></fieldList>
            </jdbcTable>
            <jdbcQuery id="expenses_usd" datasourceId="dsFoodMart">
              <fieldList><field id="as_dollars" type="java.math.BigDecimal"/></fieldList>
              <query>SELECT amount * 1.1 AS as_dollars FROM expenses</query>
            </jdbcQuery>
            <jdbcTable id="JoinTree_1" datasourceId="dsFoodMart" schemaAlias="public" datasourceTableName="customer">
              <fieldList>
                <field id="customer.account_num" fieldDBName="account_num" type="java.lang.Long"/>
                <field id="customer.city" fieldDBName="city" type="java.lang.String"/>
                <field id="store.store_name" fieldDBName="store_name" type="java.lang.String"/>
              </fieldList>
              <joinInfo alias="customer" referenceId="customer"/>
              <tableRefList><tableRef tableId="customer" tableAlias="customer"/><tableRef tableId="store" tableAlias="store"/></tableRefList>
              <joinList><join left="customer" right="store" type="inner" expr="customer.store_id == store.store_id"/></joinList>
            </jdbcTable>
          </resources>
        </schema>
        """;

    @Test void parse_tablesQueriesAndItems() throws Exception {
        DomainSchema s = new DomainSchemaParser().parse(SCHEMA.getBytes(StandardCharsets.UTF_8));
        assertThat(s.tables()).containsKeys("store", "customer", "JoinTree_1");
        assertThat(s.tables().get("JoinTree_1").joinTree()).isTrue();
        assertThat(s.tables().get("JoinTree_1").aliasToTableId()).containsEntry("store", "store").containsEntry("customer", "customer");
        assertThat(s.tables().get("store").joinTree()).isFalse();
        assertThat(s.queries().get("expenses_usd").sql()).startsWith("SELECT amount");
        assertThat(s.items()).extracting(DomainSchema.Item::id).containsExactly("city", "account_num", "store_name", "usd", "ghost");
        assertThat(s.items().get(0).groupPath()).isEqualTo("AccountsSet/Address");
        assertThat(s.items().get(2).groupPath()).isEmpty();
    }

    @Test void resolve_plainTableJoinTreeQueryAndUnknown() throws Exception {
        DomainSchema s = new DomainSchemaParser().parse(SCHEMA.getBytes(StandardCharsets.UTF_8));
        assertThat(s.resolve("store.store_name")).contains(new DomainSchema.PhysicalColumn("dsFoodMart", "public", "store", "store_name", true));
        assertThat(s.resolve("JoinTree_1.store.store_name")).contains(new DomainSchema.PhysicalColumn("dsFoodMart", "public", "store", "store_name", true));
        assertThat(s.resolve("JoinTree_1.customer.city")).contains(new DomainSchema.PhysicalColumn("dsFoodMart", "public", "customer", "city", true));
        assertThat(s.resolve("expenses_usd.as_dollars")).contains(new DomainSchema.PhysicalColumn("dsFoodMart", null, "(query:expenses_usd)", "as_dollars", false));
        assertThat(s.resolve("nowhere.x")).isEmpty();
        assertThat(s.resolve("noDot")).isEmpty();
    }
}
