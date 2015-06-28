package io.quantumdb.core.backends.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.quantumdb.core.schema.definitions.Catalog;
import io.quantumdb.core.schema.definitions.Column;
import io.quantumdb.core.schema.definitions.Sequence;
import io.quantumdb.core.schema.definitions.Table;
import io.quantumdb.core.utils.QueryBuilder;

class CatalogLoader {

	private static final Pattern SEQUENCE_EXPRESSION = Pattern.compile("nextval\\(\\'(\\w+_id_seq)\\'::regclass\\)", Pattern.CASE_INSENSITIVE);

	private final Connection connection;

	CatalogLoader(Connection connection) {
		this.connection = connection;
	}

	Catalog load(String catalogName) throws SQLException {
		Catalog catalog = new Catalog(catalogName);
		loadTables(catalog);
		return catalog;
	}

	private void loadTables(Catalog catalog) throws SQLException {
		String query = new QueryBuilder()
				.append("SELECT DISTINCT(table_name) AS table_name ")
				.append("FROM information_schema.columns ")
				.append("WHERE table_schema = ? ")
				.append("ORDER BY table_name ASC")
				.toString();

		Set<String> tableNames = Sets.newHashSet();
		try (PreparedStatement statement = connection.prepareStatement(query)) {
			statement.setString(1, "public");

			ResultSet resultSet = statement.executeQuery();
			while (resultSet.next()) {
				String tableName = resultSet.getString("table_name");
				if (tableName.startsWith("quantumdb_")) {
					// Skip quantumdb related tables, since these are versioned internally...
					continue;
				}

				tableNames.add(tableName);
			}
		}

		for (String tableName : tableNames) {
			Table table = createTable(tableName);
			catalog.addTable(table);

			table.getColumns().stream()
					.map(Column::getSequence)
					.filter(seq -> seq != null)
					.forEach(catalog::addSequence);
		}

		for (String tableName : tableNames) {
			addForeignKeys(catalog, tableName);
		}
	}

	private Table createTable(String tableName) throws SQLException {
		Table table = new Table(tableName);

		String query = new QueryBuilder()
				.append("SELECT *")
				.append("FROM information_schema.columns")
				.append("WHERE table_schema = ? AND table_name = ?")
				.append("ORDER BY ordinal_position ASC")
				.toString();

		List<Column> columns = Lists.newArrayList();
		Set<String> primaryKeys = determinePrimaryKeys(tableName);
		try (PreparedStatement statement = connection.prepareStatement(query)) {
			statement.setString(1, "public");
			statement.setString(2, tableName);

			ResultSet resultSet = statement.executeQuery();
			while (resultSet.next()) {
				String columnName = resultSet.getString("column_name");
				String expression = resultSet.getString("column_default");
				String type = resultSet.getString("data_type");
				Integer characterMaximum = null;
				if (resultSet.getObject("character_maximum_length") != null) {
					characterMaximum = resultSet.getInt("character_maximum_length");
				}

				Set<Column.Hint> hints = Sets.newHashSet();
				if (!"yes".equalsIgnoreCase(resultSet.getString("is_nullable"))) {
					hints.add(Column.Hint.NOT_NULL);
				}
				if (primaryKeys.contains(columnName) || (primaryKeys.isEmpty() && columns.isEmpty())) { // TODO: <--- HACK!!
					hints.add(Column.Hint.IDENTITY);
				}

				Sequence sequence = null;
				if (expression != null) {
					Matcher matcher = SEQUENCE_EXPRESSION.matcher(expression);
					if (matcher.find()) {
						hints.add(Column.Hint.AUTO_INCREMENT);
						sequence = new Sequence(matcher.group(1));
					}
				}

				Column.Hint[] hintArray = hints.stream().toArray(Column.Hint[]::new);

				Column column;
				if (sequence == null) {
					column = new Column(columnName, PostgresTypes.from(type, characterMaximum), expression, hintArray);
				}
				else {
					column = new Column(columnName, PostgresTypes.from(type, characterMaximum), sequence, hintArray);
				}
				columns.add(column);
			}
		}

		return table.addColumns(columns);
	}

	private Set<String> determinePrimaryKeys(String tableName) throws SQLException {
		String query = new QueryBuilder()
				.append("SELECT ")
				.append("  pg_attribute.attname AS name ")
				.append("FROM pg_index, pg_class, pg_attribute, pg_namespace ")
				.append("WHERE ")
				.append("  nspname = 'public' AND ")
				.append("  pg_class.oid = '" + tableName + "'::regclass AND ")
				.append("  indrelid = pg_class.oid AND ")
				.append("  pg_class.relnamespace = pg_namespace.oid AND ")
				.append("  pg_attribute.attrelid = pg_class.oid AND ")
				.append("  pg_attribute.attnum = any(pg_index.indkey) ")
				.append(" AND indisprimary")
				.toString();

		Set<String> primaryKeyColumns = Sets.newLinkedHashSet();
		try (Statement statement = connection.createStatement()) {

			ResultSet resultSet = statement.executeQuery(query);
			while (resultSet.next()) {
				String name = resultSet.getString("name");
				primaryKeyColumns.add(name);
			}
		}

		return primaryKeyColumns;
	}

	private void addForeignKeys(Catalog catalog, String tableName) throws SQLException {
		String query = new QueryBuilder()
				.append("SELECT")
				.append("  att2.attname AS referencing_column,")
				.append("  cl.relname AS referred_table,")
				.append("  att.attname AS referred_column,")
				.append("  con.conname AS constraint_name")
				.append("FROM")
				.append("   (SELECT")
				.append("        unnest(con1.conkey) AS parent,")
				.append("        unnest(con1.confkey) AS child,")
				.append("        con1.conname,")
				.append("        con1.confrelid,")
				.append("        con1.conrelid")
				.append("    FROM")
				.append("        pg_class cl")
				.append("        JOIN pg_namespace ns ON cl.relnamespace = ns.oid")
				.append("        JOIN pg_constraint con1 ON con1.conrelid = cl.oid")
				.append("    WHERE")
				.append("        ns.nspname = ? AND cl.relname = ? AND con1.contype = 'f'")
				.append("   ) con")
				.append("   JOIN pg_attribute att ON")
				.append("       att.attrelid = con.confrelid AND att.attnum = con.child")
				.append("   JOIN pg_class cl ON")
				.append("       cl.oid = con.confrelid")
				.append("   JOIN pg_attribute att2 ON")
				.append("       att2.attrelid = con.conrelid AND att2.attnum = con.parent")
				.append("ORDER BY con.conname ASC, parent ASC;")
				.toString();

		try (PreparedStatement statement = connection.prepareStatement(query)) {
			statement.setString(1, "public");
			statement.setString(2, tableName);
			ResultSet resultSet = statement.executeQuery();

			String prevConstraintName = null;
			String prevReferredTable = null;
			Map<String, String> mapping = Maps.newLinkedHashMap();

			while (resultSet.next()) {
				String referencingColumn = resultSet.getString("referencing_column");
				String referredTable = resultSet.getString("referred_table");
				String referredColumn = resultSet.getString("referred_column");
				String constraintName = resultSet.getString("constraint_name");

				if (prevConstraintName != null && !constraintName.equals(prevConstraintName)) {
					Table source = catalog.getTable(tableName);
					Table target = catalog.getTable(prevReferredTable);

					source.addForeignKey(Lists.newArrayList(mapping.keySet()))
							.referencing(target, Lists.newArrayList(mapping.values()));

					mapping.clear();
				}

				prevReferredTable = referredTable;
				prevConstraintName = constraintName;
				mapping.put(referencingColumn, referredColumn);
			}

			if (prevConstraintName != null) {
				Table source = catalog.getTable(tableName);
				Table target = catalog.getTable(prevReferredTable);

				source.addForeignKey(Lists.newArrayList(mapping.keySet()))
						.referencing(target, Lists.newArrayList(mapping.values()));
			}
		}
	}
}
