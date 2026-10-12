package org.polyzon;

import java.lang.reflect.RecordComponent;
import java.util.Objects;
import java.util.StringJoiner;

/** equals/hashCode/toString for result records that leave out their {@link ResponseMeta}. */
final class Records {
    private Records() {
    }

    static boolean equalsIgnoringMeta(Record self, Object other) {
        if (self == other) {
            return true;
        }
        if (other == null || other.getClass() != self.getClass()) {
            return false;
        }
        for (RecordComponent component : self.getClass().getRecordComponents()) {
            if (!isMeta(component) && !Objects.equals(value(component, self), value(component, other))) {
                return false;
            }
        }
        return true;
    }

    static int hashIgnoringMeta(Record self) {
        int hash = 0;
        for (RecordComponent component : self.getClass().getRecordComponents()) {
            if (!isMeta(component)) {
                hash = 31 * hash + Objects.hashCode(value(component, self));
            }
        }
        return hash;
    }

    static String toStringIgnoringMeta(Record self) {
        StringJoiner out = new StringJoiner(", ", self.getClass().getSimpleName() + "[", "]");
        for (RecordComponent component : self.getClass().getRecordComponents()) {
            if (!isMeta(component)) {
                out.add(component.getName() + "=" + value(component, self));
            }
        }
        return out.toString();
    }

    private static boolean isMeta(RecordComponent component) {
        return component.getType() == ResponseMeta.class;
    }

    private static Object value(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException impossible) {
            // Public accessors of public records in this module are always accessible.
            throw new IllegalStateException(impossible);
        }
    }
}
