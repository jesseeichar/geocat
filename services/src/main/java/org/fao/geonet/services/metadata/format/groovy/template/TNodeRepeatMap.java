package org.fao.geonet.services.metadata.format.groovy.template;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import org.fao.geonet.SystemInfo;
import org.xml.sax.Attributes;

import java.io.IOException;
import java.util.Map;

/**
 * @author Jesse on 11/29/2014.
 */
public class TNodeRepeatMap extends TNode {
    private final String key, rowKeyName, rowValueName;

    public TNodeRepeatMap(SystemInfo info, String qName, Attributes attributes, String key, String rowKeyName, String rowValueName)
            throws IOException {
        super(info, qName, attributes);
        this.key = key;
        this.rowKeyName = rowKeyName;
        this.rowValueName = rowValueName;
    }

    @Override
    public void render(TRenderContext context) throws IOException {
        final Object modelValue = context.getModelValue(this.key);

        if (!(modelValue instanceof Map)) {
            throw new TemplateException(
                    "Expected a map for (" + rowKeyName + ", " + rowValueName + ") in " + this.key + " but got a " + modelValue);

        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map) modelValue;

        if (map.isEmpty() && this.info.isDevMode()) {
            context.append("<!-- fmt-repeat: (").append(rowKeyName).append(", ").append(rowValueName).append(") in ").append(this.key)
                    .append(" is empty -->");
        }

        int i = 0;
        int size = map.size();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Map<String, Object> newModelMap = Maps.newHashMap();
            newModelMap.put(this.rowKeyName, entry.getKey());
            newModelMap.put(this.rowValueName, entry.getValue());
            TNodeRepeatIter.addIndexInfo(newModelMap, i, size);

            TRenderContext childContext = context.childContext(newModelMap);

            context.append("<").append(qName);
            attributes.render(childContext);
            context.append(">");

            for (TNode node : getChildren()) {
                node.render(childContext);
            }
            end.render(childContext);

            i++;
        }
    }

    @Override
    protected Attributes customAttributes(TRenderContext context) {
        return null;
    }

    @Override
    protected Optional<String> canRender(TRenderContext context) {
        return Optional.absent();
    }
}
