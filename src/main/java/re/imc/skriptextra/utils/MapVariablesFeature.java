package re.imc.skriptextra.utils;

import ch.njol.skript.Skript;
import ch.njol.skript.aliases.ItemType;
import ch.njol.skript.config.Node;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionList;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.lang.Section;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Kleenean;
import com.btk5h.skriptmirror.skript.reflect.sections.SectionEvent;
import com.btk5h.skriptmirror.util.SkriptUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.entry.EntryContainer;
import org.skriptlang.skript.lang.entry.EntryData;
import org.skriptlang.skript.lang.entry.EntryValidator;
import org.skriptlang.skript.lang.entry.util.ExpressionEntryData;
import re.imc.skriptextra.utils.LogUtils;
import re.imc.skriptextra.utils.PluginConfig;

public final class MapVariablesFeature {
    private MapVariablesFeature() {
    }

    public static void register() {
        if (!PluginConfig.enabled("features.map-variables.enabled")) {
            return;
        }
        if (PluginConfig.bool("features.map-variables.disable-when-vessel-present", true) && Bukkit.getPluginManager().getPlugin("Vessel") != null) {
            LogUtils.info("检测到 Vessel，已禁用 map 变量功能以避免语法冲突");
            return;
        }
        boolean embeddedSections = PluginConfig.bool("features.map-variables.embedded-sections", true);
        if (embeddedSections && !Bukkit.getPluginManager().isPluginEnabled("skript-reflect")) {
            LogUtils.warn("未检测到 skript-reflect，map 变量功能未启用");
            return;
        }
        if (embeddedSections) {
            Skript.registerEffect(MapSectionEffect.class, (String[])new String[]{PluginConfig.string("features.map-variables.embedded-section-pattern", "sec[tion] <.+> [with [arguments] [variables] %-objects%]")});
        }
        Skript.registerSection(MapVariablesSection.class, (String[])new String[]{PluginConfig.string("features.map-variables.pattern", "(set var[iable]s|map) %objects%")});
        LogUtils.info("已启用 map 变量功能");
    }

    private static Expression<?> parseValue(String text) {
        return new SkriptParser(text, 3, ParseContext.DEFAULT).parseExpression(new Class[]{Number.class, ItemType.class, Object.class});
    }

    private static StringKey parseKey(String text) {
        if (!text.contains("%")) {
            return new StringKey().append(text);
        }
        Object expressionText = text.startsWith("\"") ? text : "\"" + text + "\"";
        return new StringKey().append(new SkriptParser((String)expressionText, 3, ParseContext.DEFAULT).parseExpression(new Class[]{Number.class, ItemType.class, Object.class}));
    }

    public static final class MapSectionEffect
    extends Effect {
        private Trigger trigger;
        private StringKey parentKey;
        private MapVariablesSection parent;
        private boolean local;
        private List<Variable<?>> arguments;
        private StringKey key;

        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean delay, SkriptParser.ParseResult parseResult) {
            this.key = MapVariablesFeature.parseKey(((MatchResult)parseResult.regexes.get(0)).group());
            this.arguments = new ArrayList();
            Expression argumentExpression = SkriptUtil.defendExpression(expressions[0]);
            if (argumentExpression == null) {
                return true;
            }
            if (argumentExpression instanceof ExpressionList) {
                ExpressionList list = (ExpressionList)argumentExpression;
                for (Expression expression : list.getExpressions()) {
                    if (this.addArgument(expression)) continue;
                    return false;
                }
                return true;
            }
            return this.addArgument(argumentExpression);
        }

        private boolean addArgument(Expression<?> expression) {
            if (!(expression instanceof Variable)) {
                Skript.error((String)"The arguments can only contain variables");
                return false;
            }
            Variable variable = (Variable)expression;
            this.arguments.add(variable);
            return true;
        }

        protected void execute(Event event) {
            String variableName = this.parent.variable.getName().toString(event);
            String baseName = variableName.substring(0, variableName.length() - 3);
            String sectionName = this.parentKey.copy().append(this.key).get(event);
            Variables.setVariable((String)(baseName + "::" + sectionName), (Object)new com.btk5h.skriptmirror.skript.reflect.sections.Section(this.trigger, event, this.arguments), (Event)event, (boolean)this.local);
        }

        public String toString(@Nullable Event event, boolean debug) {
            return "section";
        }
    }

    public static final class MapVariablesSection
    extends Section {
        private final Set<MapSectionEffect> sections = new HashSet<MapSectionEffect>();
        private final Map<StringKey, Expression<?>> expressions = new HashMap();
        private boolean local;
        private Variable<?> variable;

        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean delay, SkriptParser.ParseResult parseResult, SectionNode node, List<TriggerItem> triggerItems) {
            Variable parsedVariable;
            Expression<?> expression = expressions[0];
            if (!(expression instanceof Variable) || !(parsedVariable = (Variable)expression).isList()) {
                return false;
            }
            this.variable = parsedVariable;
            this.local = this.variable.isLocal();
            return this.parseNodes(new StringKey(), node);
        }

        private boolean parseNodes(StringKey path, SectionNode sectionNode) {
            EntryValidator.EntryValidatorBuilder builder = EntryValidator.builder();
            HashSet<String> entries = new HashSet<String>();
            HashSet<String> sectionsToRemove = new HashSet<String>();
            boolean array = false;
            int index = PluginConfig.integer("features.map-variables.array-start-index", 1);
            for (Node node : sectionNode) {
                String nodeText = node.getKey();
                if (nodeText == null) continue;
                if (node instanceof SectionNode) {
                    SectionNode child = (SectionNode)node;
                    if (PluginConfig.bool("features.map-variables.embedded-sections", true) && (child.getKey().startsWith("section ") || child.getKey().startsWith("sec "))) {
                        String effectText = child.getKey().endsWith(":") ? child.getKey().substring(0, child.getKey().length() - 1) : child.getKey();
                        MapSectionEffect effect = (MapSectionEffect)Effect.parse((String)effectText, (String)("Can't understand this effect: " + effectText));
                        if (effect != null) {
                            effect.trigger = this.loadCode(child, "section", new Class[]{SectionEvent.class});
                            effect.parentKey = path.copy();
                            effect.parent = this;
                            effect.local = this.local;
                            this.sections.add(effect);
                            sectionsToRemove.add(nodeText);
                            continue;
                        }
                        Skript.warning((String)("Invalid section effect: " + effectText + ", it's a key?"));
                    }
                    builder.addSection(child.getKey(), true);
                    if (this.parseNodes(path.copy().append(MapVariablesFeature.parseKey(child.getKey())).append("::"), child)) continue;
                    return false;
                }
                if (nodeText.startsWith("- ")) {
                    this.expressions.put(path.copy().append(String.valueOf(index++)), MapVariablesFeature.parseValue(nodeText.substring(2)));
                    array = true;
                    continue;
                }
                String entry = nodeText.split(": ", 2)[0];
                entries.add(entry);
                builder.addEntryData((EntryData)new FixedExpressionEntryData(entry));
            }
            if (array) {
                return true;
            }
            for (String section : sectionsToRemove) {
                sectionNode.remove(section);
            }
            EntryContainer container = builder.build().validate(sectionNode);
            if (container == null) {
                return false;
            }
            for (String entry : entries) {
                this.expressions.put(path.copy().append(MapVariablesFeature.parseKey(entry)), (Expression)container.getOptional(entry, Expression.class, true));
            }
            return true;
        }

        @Nullable
        protected TriggerItem walk(Event event) {
            String variableName = this.variable.getName().toString(event);
            String baseName = variableName.substring(0, variableName.length() - 3);
            if (PluginConfig.bool("features.map-variables.clear-before-map", false)) {
                Variables.setVariable((String)variableName, null, (Event)event, (boolean)this.local);
            }
            for (Map.Entry<StringKey, Expression<?>> entry : this.expressions.entrySet()) {
                Object[] values = entry.getValue().getAll(event);
                String key = entry.getKey().get(event);
                if (values.length == 1) {
                    Variables.setVariable((String)(baseName + "::" + key), (Object)values[0], (Event)event, (boolean)this.local);
                    continue;
                }
                for (int index = 0; index < values.length; ++index) {
                    Variables.setVariable((String)(baseName + "::" + key + "::" + (index + 1)), (Object)values[index], (Event)event, (boolean)this.local);
                }
            }
            for (MapSectionEffect section : this.sections) {
                section.execute(event);
            }
            return this.getNext();
        }

        public String toString(@Nullable Event event, boolean debug) {
            return "map";
        }
    }

    public static final class StringKey {
        private final List<Object> parts = new ArrayList<Object>();

        public StringKey append(String text) {
            this.parts.add(text);
            return this;
        }

        public StringKey append(Expression<?> expression) {
            this.parts.add(expression);
            return this;
        }

        public StringKey append(StringKey key) {
            this.parts.addAll(key.parts);
            return this;
        }

        public String get(Event event) {
            StringBuilder key = new StringBuilder();
            for (Object part : this.parts) {
                Expression expression;
                Object value;
                if (part instanceof String) {
                    String text = (String)part;
                    key.append(text);
                    continue;
                }
                if (!(part instanceof Expression) || (value = (expression = (Expression)part).getSingle(event)) == null) continue;
                key.append(value);
            }
            return key.toString();
        }

        public StringKey copy() {
            StringKey copy = new StringKey();
            copy.parts.addAll(this.parts);
            return copy;
        }
    }

    private static final class FixedExpressionEntryData
    extends ExpressionEntryData<Object> {
        private FixedExpressionEntryData(String key) {
            super(key, null, true, Object.class);
        }

        @Nullable
        protected Expression<?> getValue(String value) {
            return new SkriptParser(value, 3, ParseContext.DEFAULT).parseExpression(new Class[]{Number.class, Object.class});
        }
    }
}
