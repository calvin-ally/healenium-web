/**
 * Healenium-web Copyright (C) 2019 EPAM
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.healenium;

import com.epam.healenium.annotation.DisableHealing;
import com.epam.healenium.client.RestClient;
import com.epam.healenium.treecomparing.HeuristicNodeDistance;
import com.epam.healenium.treecomparing.JsoupHTMLParser;
import com.epam.healenium.treecomparing.LCSPathDistance;
import com.epam.healenium.treecomparing.Node;
import com.epam.healenium.treecomparing.NodeBuilder;
import com.epam.healenium.treecomparing.Path;
import com.epam.healenium.treecomparing.PathFinder;
import com.epam.healenium.treecomparing.Scored;
import com.epam.healenium.utils.ResourceReader;
import com.epam.healenium.utils.StackUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebElement;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.epam.healenium.SelectorComponent.*;

@Slf4j
public class SelfHealingEngine {

    /**
     * A JavaScript source to extract an HTML item with its attributes
     */
    private static final String SCRIPT = ResourceReader.readResource(
            "itemsWithAttributes.js", s -> s.collect(Collectors.joining()));
    private static final Config DEFAULT_CONFIG = ConfigFactory.systemProperties().withFallback(
            ConfigFactory.load("healenium.properties").withFallback(ConfigFactory.load()));

    @Getter
    private final Config config;
    @Getter
    private final WebDriver webDriver;
    private final int recoveryTries;
    private final double scoreCap;
    private final List<Set<SelectorComponent>> selectorDetailLevels;
    @Getter
    private final RestClient client;

    /**
     * @param delegate a delegate driver, not actually {@link SelfHealingDriver} instance.
     * @param config   user-defined configuration
     */
    public SelfHealingEngine(@NotNull WebDriver delegate, @NotNull Config config) {
        // merge given config with default values
        Config finalizedConfig = ConfigFactory.load(config).withFallback(DEFAULT_CONFIG);

        List<Set<SelectorComponent>> temp = new ArrayList<>();
        temp.add(EnumSet.of(TAG, ID));
        temp.add(EnumSet.of(TAG, CLASS));
        temp.add(EnumSet.of(PARENT, TAG, ID, CLASS));
        temp.add(EnumSet.of(PARENT, TAG, CLASS, POSITION));
        temp.add(EnumSet.of(PARENT, TAG, ID, CLASS, ATTRIBUTES));
        temp.add(EnumSet.of(PATH));

        this.webDriver = delegate;
        this.config = finalizedConfig;
        this.recoveryTries = finalizedConfig.getInt("recovery-tries");
        this.scoreCap = finalizedConfig.getDouble("score-cap");
        this.selectorDetailLevels = Collections.unmodifiableList(temp);
        this.client = new RestClient(finalizedConfig);
    }

    /**
     * Used, when client not override config explicitly
     *
     * @param delegate
     */
    public SelfHealingEngine(@NotNull WebDriver delegate) {
        this(delegate, DEFAULT_CONFIG);
    }

    /**
     * Stores the valid locator state: the element it found and the page.
     *
     * @param by          the locator
     * @param webElements the elements while it is still accessible by the locator
     */
    public void savePath(PageAwareBy by, List<WebElement> webElements) {
        savePath(by, webElements, new ArrayList<>());
    }

    /**
     * Stores the valid locator state: the element it found and the page.
     *
     * @param by          the locator
     * @param nodesToHeal the nodes to save
     * @param pageElements the elements while it is still accessible by the locator
     */
    public void savePath(PageAwareBy by, List<WebElement> pageElements, List<List<Node>> nodesToHeal) {
        List<List<Node>> nodesToSave = new ArrayList<>(nodesToHeal);
        pageElements.forEach(e -> nodesToSave.add(getNodePath(e)));
        save(by, Thread.currentThread().getStackTrace(), nodesToSave);
    }

    public List<Node> getNodePath(WebElement webElement) {
        JavascriptExecutor executor = (JavascriptExecutor) webDriver;
        String data = (String) executor.executeScript(SCRIPT, webElement);
        List<Node> path = new LinkedList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode treeNode = mapper.readTree(data);
            if (treeNode.isArray()) {
                for (final JsonNode jsonNode : treeNode) {
                    Node node = toNode(mapper.treeAsTokens(jsonNode));
                    path.add(node);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to get element node path!", ex);
        }
        return path;
    }

    public List<Scored<By>> findNewLocations(PageAwareBy by, String targetPage) {
        return findNewLocations(by, targetPage, StackUtils.findOriginCaller());
    }

    /**
     * Convert raw data to {@code Node}
     *
     * @param parser - JSON reader
     * @return path node
     * @throws IOException
     */
    private Node toNode(JsonParser parser) throws IOException {
        ObjectCodec codec = parser.getCodec();
        TreeNode tree = parser.readValueAsTree();
        String tag = codec.treeToValue(tree.path(FieldName.TAG), String.class);
        Integer index = codec.treeToValue(tree.path(FieldName.INDEX), Integer.class);
        String innerText = codec.treeToValue(tree.path(FieldName.INNER_TEXT), String.class);
        String id = codec.treeToValue(tree.path(FieldName.ID), String.class);
        //noinspection unchecked
        Set<String> classes = codec.treeToValue(tree.path(FieldName.CLASSES), Set.class);
        //noinspection unchecked
        Map<String, String> attributes = codec.treeToValue(tree.path(FieldName.OTHER), Map.class);
        return new NodeBuilder()
                //TODO: fix attribute setting, because they override 'id' and 'classes' property
                .setAttributes(attributes)
                .setTag(tag)
                .setIndex(index)
                .setId(id)
                .addContent(innerText)
                .setClasses(classes)
                .build();
    }

    /**
     * @param by         page aware locator
     * @param targetPage the new HTML page source on which we should search for the element
     * @return a list of candidate locators, ordered by revelance, or empty list if was unable to heal
     */
    public List<Scored<By>> findNewLocations(PageAwareBy by, String targetPage,
                                             Optional<StackTraceElement> optionalElement) {
        List<Scored<By>> result = new ArrayList<>();
        getLastValidPaths(by, optionalElement)
                .ifPresent(nodes -> findNearest(nodes.get(0).toArray(new Node[0]), targetPage).stream()
                        .map(this::toLocator)
                        .forEach(result::add));
        return result;
    }

    public List<Scored<By>> findNewLocationsByNodes(List<Node> nodes, String targetPage) {
        List<Scored<By>> result = new ArrayList<>();
        findNearest(nodes.toArray(new Node[0]), targetPage).stream()
                .map(this::toLocator)
                .forEach(result::add);
        return result;
    }

    public Optional<List<List<Node>>> findNodesToHeal(PageAwareBy pageBy, List<WebElement> pageElements,
                                                      StackTraceElement[] stackTrace) {
        Optional<List<List<Node>>> lastValidPath = getLastValidPaths(pageBy, StackUtils.findOriginCaller(stackTrace));
        List<String> elementIds = pageElements.stream()
                .map(e -> ((RemoteWebElement) e).getId())
                .collect(Collectors.toList());
        List<List<Node>> needToHealElements = new ArrayList<>();
        lastValidPath.ifPresent(path -> path
                .forEach(nodes -> {
                    WebElement element = nodeToElementConverter(nodes.get(nodes.size() - 1));
                    if (element == null || !elementIds.contains(((RemoteWebElement) element).getId())) {
                        needToHealElements.add(nodes);
                    }
                }));
        return Optional.of(needToHealElements);
    }

    public void save(PageAwareBy key, StackTraceElement[] stackTrace, List<List<Node>> elementsToSave) {
        StackTraceElement traceElement = StackUtils.findOriginCaller(stackTrace)
                .orElseThrow(() -> new IllegalArgumentException("Failed to detect origin method caller"));
        client.selectorsRequest(key.getBy(), traceElement, new ArrayList<>(elementsToSave));
    }

    private WebElement nodeToElementConverter(Node node) {
        for (Set<SelectorComponent> detailLevel : selectorDetailLevels) {
            By locator = construct(node, detailLevel);
            List<WebElement> elements = webDriver.findElements(locator);
            if (elements.size() == 1) {
                return elements.get(0);
            }
        }
        return null;
    }

    private Scored<By> toLocator(Scored<Node> node) {
        for (Set<SelectorComponent> detailLevel : selectorDetailLevels) {
            By locator = construct(node.getValue(), detailLevel);
            List<WebElement> elements = webDriver.findElements(locator);
            if (elements.size() == 1) {
                return new Scored<>(node.getScore(), locator);
            }
        }
        throw new HealException();
    }

    private By construct(Node node, Set<SelectorComponent> detailLevel) {
        return By.cssSelector(detailLevel.stream()
                .map(component -> component.createComponent(node))
                .collect(Collectors.joining()));
    }

    /**
     * @param nodePath        the array of nodes which actually represent the full path of an element in HTML tree,
     *                        ordered from deepest to shallowest
     * @param destinationTree the HTML code of the current page
     * @return a list of nodes which are the candidates to be the searched element, ordered by relevance descending.
     */
    private List<Scored<Node>> findNearest(Node[] nodePath, String destinationTree) {
        Node destination = parseTree(destinationTree);
        PathFinder pathFinder = new PathFinder(new LCSPathDistance(), new HeuristicNodeDistance());
        return pathFinder.find(new Path(nodePath), destination, recoveryTries, scoreCap);
    }

    private Node parseTree(String tree) {
        return new JsoupHTMLParser().parse(new ByteArrayInputStream(tree.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean isHealingEnabled() {
        boolean isDisabled = StackUtils.isAnnotationPresent(DisableHealing.class);
        return config.getBoolean("heal-enabled") && !isDisabled;
    }

    public Optional<List<List<Node>>> getLastValidPaths(PageAwareBy key, Optional<StackTraceElement> optionalElement) {
        return optionalElement
                .flatMap(it -> client.getLastValidPath(key.getBy(), it))
                .filter(it -> !it.isEmpty());
    }

}
