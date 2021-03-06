package org.batfish.question.searchfilters;

import static org.batfish.question.testfilters.TestFiltersAnswerer.COL_FILTER_NAME;
import static org.batfish.question.testfilters.TestFiltersAnswerer.COL_NODE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.batfish.common.Answerer;
import org.batfish.common.BatfishException;
import org.batfish.common.Pair;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpAccessListLine;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.acl.AclLineMatchExpr;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.answers.Schema;
import org.batfish.datamodel.questions.Question;
import org.batfish.datamodel.table.ColumnMetadata;
import org.batfish.datamodel.table.Row;
import org.batfish.datamodel.table.Row.RowBuilder;
import org.batfish.datamodel.table.TableAnswerElement;
import org.batfish.datamodel.table.TableDiff;
import org.batfish.datamodel.table.TableMetadata;
import org.batfish.question.SearchFiltersParameters;
import org.batfish.question.testfilters.TestFiltersAnswerer;
import org.batfish.question.testfilters.TestFiltersQuestion;
import org.batfish.specifier.FilterSpecifier;
import org.batfish.specifier.SpecifierContext;

/** Answerer for SearchFiltersQuestion */
public final class SearchFiltersAnswerer extends Answerer {
  public static final String COL_HEADER_SPACE = "Header_Space";
  private TableAnswerElement _tableAnswerElement;

  public SearchFiltersAnswerer(Question question, IBatfish batfish) {
    super(question, batfish);
  }

  @Override
  public AnswerElement answer() {
    SearchFiltersQuestion question = (SearchFiltersQuestion) _question;
    nonDifferentialAnswer(question);
    return _tableAnswerElement;
  }

  @Override
  public AnswerElement answerDiff() {
    differentialAnswer((SearchFiltersQuestion) _question);
    return _tableAnswerElement;
  }

  private void differentialAnswer(SearchFiltersQuestion question) {
    _batfish.pushBaseSnapshot();
    Map<String, Configuration> baseConfigs = _batfish.loadConfigurations();
    Multimap<String, String> baseAcls = getSpecifiedAcls(question);
    _batfish.popSnapshot();

    _batfish.pushDeltaSnapshot();
    Map<String, Configuration> deltaConfigs = _batfish.loadConfigurations();
    Multimap<String, String> deltaAcls = getSpecifiedAcls(question);
    _batfish.popSnapshot();

    SearchFiltersParameters parameters = question.toSearchFiltersParameters();

    TableAnswerElement baseTable =
        toSearchFiltersTable(
            TestFiltersAnswerer.create(new TestFiltersQuestion(null, null, null, null)),
            question.getGenerateExplanations());
    TableAnswerElement deltaTable =
        toSearchFiltersTable(
            TestFiltersAnswerer.create(new TestFiltersQuestion(null, null, null, null)),
            question.getGenerateExplanations());

    Set<String> commonNodes = Sets.intersection(baseAcls.keySet(), deltaAcls.keySet());
    for (String node : commonNodes) {
      Configuration baseConfig = baseConfigs.get(node);
      Configuration deltaConfig = deltaConfigs.get(node);

      Set<String> commonAcls =
          Sets.intersection(
              ImmutableSet.copyOf(baseAcls.get(node)), ImmutableSet.copyOf(deltaAcls.get(node)));

      for (String aclName : commonAcls) {
        Optional<IpAccessList> baseAcl = makeQueryAcl(baseConfig.getIpAccessLists().get(aclName));
        Optional<IpAccessList> deltaAcl = makeQueryAcl(deltaConfig.getIpAccessLists().get(aclName));
        if (!baseAcl.isPresent() && !deltaAcl.isPresent()) {
          continue;
        }
        if (baseAcl.isPresent() && !deltaAcl.isPresent() && question.getIncludeOneTableKeys()) {
          baseTable.addRow(
              Row.builder(baseTable.getMetadata().toColumnMap())
                  .put(COL_NODE, node)
                  .put(COL_FILTER_NAME, aclName)
                  .build());
          continue;
        }
        if (!baseAcl.isPresent() && deltaAcl.isPresent() && question.getIncludeOneTableKeys()) {
          deltaTable.addRow(
              Row.builder(deltaTable.getMetadata().toColumnMap())
                  .put(COL_NODE, node)
                  .put(COL_FILTER_NAME, aclName)
                  .build());
          continue;
        }

        // present in both snapshot
        DifferentialSearchFiltersResult results =
            _batfish.differentialReachFilter(
                baseConfig, baseAcl.get(), deltaConfig, deltaAcl.get(), parameters);

        Stream.of(results.getDecreasedResult(), results.getIncreasedResult())
            .filter(Optional::isPresent)
            .map(Optional::get)
            .forEach(
                result -> {
                  Flow flow = result.getExampleFlow();
                  AclLineMatchExpr description = result.getHeaderSpaceDescription().orElse(null);
                  baseTable.addRow(
                      toSearchFiltersRow(
                          description,
                          testFiltersRow(true, node, baseAcl.get().getName(), flow),
                          question.getGenerateExplanations()));
                  deltaTable.addRow(
                      toSearchFiltersRow(
                          description,
                          testFiltersRow(false, node, deltaAcl.get().getName(), flow),
                          question.getGenerateExplanations()));
                });
      }
    }

    // take care of nodes that are present in only one snapshot
    if (question.getIncludeOneTableKeys()) {
      addOneSnapshotNodes(Sets.difference(baseAcls.keySet(), deltaAcls.keySet()), baseTable);
      addOneSnapshotNodes(Sets.difference(deltaAcls.keySet(), baseAcls.keySet()), deltaTable);
    }

    TableAnswerElement diffTable =
        TableDiff.diffTables(baseTable, deltaTable, question.getIncludeOneTableKeys());

    _tableAnswerElement = new TableAnswerElement(diffTable.getMetadata());
    _tableAnswerElement.postProcessAnswer(question, diffTable.getRows().getData());
  }

  private static TableAnswerElement toSearchFiltersTable(
      TableAnswerElement tableAnswerElement, boolean generateExplanation) {
    Map<String, ColumnMetadata> columnMap = tableAnswerElement.getMetadata().toColumnMap();

    List<ColumnMetadata> columnMetadata = new ArrayList<>();
    columnMetadata.add(columnMap.get(TestFiltersAnswerer.COL_NODE));
    columnMetadata.add(columnMap.get(TestFiltersAnswerer.COL_FILTER_NAME));
    if (generateExplanation) {
      columnMetadata.add(
          new ColumnMetadata(COL_HEADER_SPACE, Schema.STRING, "Description of HeaderSpace"));
    }
    columnMetadata.add(columnMap.get(TestFiltersAnswerer.COL_FLOW));
    columnMetadata.add(columnMap.get(TestFiltersAnswerer.COL_ACTION));
    columnMetadata.add(columnMap.get(TestFiltersAnswerer.COL_LINE_CONTENT));
    columnMetadata.add(columnMap.get(TestFiltersAnswerer.COL_TRACE));

    TableMetadata metadata = new TableMetadata(columnMetadata);
    return new TableAnswerElement(metadata);
  }

  private static Row toSearchFiltersRow(
      @Nullable AclLineMatchExpr description, Row row, boolean generateExplanations) {

    RowBuilder rowBuilder = Row.builder().putAll(row, row.getColumnNames());

    if (generateExplanations) {
      /*
       * Sending the explanation to the client as a JSON blob. TODO: do something better.
       */
      String jsonDescription;
      try {
        jsonDescription = BatfishObjectMapper.writeString(description);
      } catch (JsonProcessingException e) {
        e.printStackTrace();
        jsonDescription = e.getMessage();
      }

      rowBuilder.put(COL_HEADER_SPACE, jsonDescription);
    }

    return rowBuilder.build();
  }

  private void nonDifferentialAnswer(SearchFiltersQuestion question) {
    List<Pair<String, IpAccessList>> acls = getQueryAcls(question);
    if (acls.isEmpty()) {
      throw new BatfishException("No matching filters");
    }

    Multiset<Row> rows = HashMultiset.create();
    /*
     * For each query ACL, try to get a flow. If one exists, run traceFilter on that flow.
     * Concatenate the answers for all flows into one big table.
     */
    Map<String, Configuration> configurations = _batfish.loadConfigurations();
    for (Pair<String, IpAccessList> pair : acls) {
      String hostname = pair.getFirst();
      Configuration node = configurations.get(hostname);
      IpAccessList acl = pair.getSecond();
      Optional<SearchFiltersResult> optionalResult;
      try {
        optionalResult = _batfish.reachFilter(node, acl, question.toSearchFiltersParameters());
      } catch (Throwable t) {
        _batfish.getLogger().warn(t.getMessage());
        continue;
      }
      optionalResult.ifPresent(
          result ->
              rows.add(
                  toSearchFiltersRow(
                      result.getHeaderSpaceDescription().orElse(null),
                      testFiltersRow(true, hostname, acl.getName(), result.getExampleFlow()),
                      question.getGenerateExplanations())));
    }

    _tableAnswerElement =
        toSearchFiltersTable(
            TestFiltersAnswerer.create(new TestFiltersQuestion(null, null, null, null)),
            question.getGenerateExplanations());
    _tableAnswerElement.postProcessAnswer(question, rows);
  }

  private Multimap<String, String> getSpecifiedAcls(SearchFiltersQuestion question) {
    SortedMap<String, Configuration> configs = _batfish.loadConfigurations();
    FilterSpecifier filterSpecifier = question.getFilterSpecifier();
    SpecifierContext specifierContext = _batfish.specifierContext();
    ImmutableMultimap.Builder<String, String> acls = ImmutableMultimap.builder();
    question
        .getNodesSpecifier()
        .resolve(_batfish.specifierContext())
        .stream()
        .map(configs::get)
        .forEach(
            config ->
                filterSpecifier
                    .resolve(config.getHostname(), specifierContext)
                    .forEach(acl -> acls.put(config.getHostname(), acl.getName())));
    return acls.build();
  }

  private Optional<IpAccessList> makeQueryAcl(IpAccessList originalAcl) {
    SearchFiltersQuestion question = (SearchFiltersQuestion) _question;
    switch (question.getType()) {
      case PERMIT:
        return Optional.of(originalAcl);
      case DENY:
        return Optional.of(toDenyAcl(originalAcl));
      case MATCH_LINE:
        // for each ACL, construct a new ACL that accepts if and only if the specified line matches
        Integer lineNumber = question.getLineNumber();
        Preconditions.checkState(
            lineNumber != null, "Cannot perform a match line query without a line number");
        return originalAcl.getLines().size() > lineNumber
            ? Optional.of(toMatchLineAcl(lineNumber, originalAcl))
            : Optional.empty();
      default:
        throw new BatfishException("Unexpected query Type: " + question.getType());
    }
  }

  @VisibleForTesting
  List<Pair<String, IpAccessList>> getQueryAcls(SearchFiltersQuestion question) {
    Map<String, Configuration> configs = _batfish.loadConfigurations();
    return getSpecifiedAcls(question)
        .entries()
        .stream()
        .map(
            entry -> {
              String hostName = entry.getKey();
              String aclName = entry.getValue();
              Optional<IpAccessList> queryAcl =
                  makeQueryAcl(configs.get(hostName).getIpAccessLists().get(aclName));
              return queryAcl.map(acl -> new Pair<>(hostName, acl));
            })
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(ImmutableList.toImmutableList());
  }

  @VisibleForTesting
  static IpAccessList toMatchLineAcl(Integer lineNumber, IpAccessList acl) {
    List<IpAccessListLine> lines =
        Streams.concat(
                acl.getLines()
                    .subList(0, lineNumber)
                    .stream()
                    .map(l -> l.toBuilder().setAction(LineAction.DENY).build()),
                Stream.of(
                    acl.getLines()
                        .get(lineNumber)
                        .toBuilder()
                        .setAction(LineAction.PERMIT)
                        .build()))
            .collect(ImmutableList.toImmutableList());
    return IpAccessList.builder().setName(acl.getName()).setLines(lines).build();
  }

  @VisibleForTesting
  static IpAccessList toDenyAcl(IpAccessList acl) {
    List<IpAccessListLine> lines =
        Streams.concat(
                acl.getLines()
                    .stream()
                    .map(
                        l ->
                            l.toBuilder()
                                // flip action
                                .setAction(
                                    l.getAction() == LineAction.PERMIT
                                        ? LineAction.DENY
                                        : LineAction.PERMIT)
                                .build()),
                // accept if we reach the end of the ACL
                Stream.of(IpAccessListLine.ACCEPT_ALL))
            .collect(ImmutableList.toImmutableList());
    return IpAccessList.builder().setName(acl.getName()).setLines(lines).build();
  }

  private Row testFiltersRow(boolean base, String hostname, String aclName, Flow flow) {
    if (base) {
      _batfish.pushBaseSnapshot();
    } else {
      _batfish.pushDeltaSnapshot();
    }
    Configuration c = _batfish.loadConfigurations().get(hostname);
    Row row = TestFiltersAnswerer.getRow(c.getIpAccessLists().get(aclName), flow, c);
    _batfish.popSnapshot();
    return row;
  }

  /** Adds {@code nodes} (which are present in only one snapshot) to the {@code table} */
  private void addOneSnapshotNodes(SetView<String> nodes, TableAnswerElement table) {
    for (String node : nodes) {
      table.addRow(Row.builder(table.getMetadata().toColumnMap()).put(COL_NODE, node).build());
    }
  }
}
