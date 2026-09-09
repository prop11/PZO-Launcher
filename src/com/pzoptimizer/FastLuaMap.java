package com.pzoptimizer;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Stores contiguous positive integer keys in an array; other keys use LinkedHashMap. */
public class FastLuaMap extends AbstractMap<Object, Object> {

    private Object[] arrayPart = new Object[16];
    private int arrayLen = 0;
    private final LinkedHashMap<Object, Object> mapPart = new LinkedHashMap<>();

    private static int getIntKey(Object key) {
        if (key instanceof Number) {
            double d = ((Number) key).doubleValue();
            int i = (int) d;
            if (d == (double) i && i >= 1 && i <= 1048576) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Object get(Object key) {
        int ik = getIntKey(key);
        if (ik >= 1 && ik <= arrayPart.length) {
            return arrayPart[ik - 1];
        }
        return mapPart.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        int ik = getIntKey(key);
        if (ik >= 1 && ik <= arrayPart.length) {
            return arrayPart[ik - 1] != null;
        }
        return mapPart.containsKey(key);
    }

    @Override
    public Object put(Object key, Object value) {
        int ik = getIntKey(key);
        if (ik >= 1) {
            int idx = ik - 1;
            if (idx >= arrayPart.length) {
                if (value == null) return null;
                int newCap = Math.max(arrayPart.length * 2, idx + 1);
                Object[] newArr = new Object[newCap];
                System.arraycopy(arrayPart, 0, newArr, 0, arrayPart.length);
                arrayPart = newArr;
            }
            Object old = arrayPart[idx];
            arrayPart[idx] = value;
            if (ik > arrayLen && value != null) {
                arrayLen = ik;
            } else if (ik == arrayLen && value == null) {
                while (arrayLen > 0 && arrayPart[arrayLen - 1] == null) {
                    arrayLen--;
                }
            }
            return old;
        }
        return mapPart.put(key, value);
    }

    @Override
    public Object remove(Object key) {
        int ik = getIntKey(key);
        if (ik >= 1 && ik <= arrayPart.length) {
            int idx = ik - 1;
            Object old = arrayPart[idx];
            arrayPart[idx] = null;
            if (ik == arrayLen) {
                while (arrayLen > 0 && arrayPart[arrayLen - 1] == null) {
                    arrayLen--;
                }
            }
            return old;
        }
        return mapPart.remove(key);
    }

    @Override
    public void clear() {
        Arrays.fill(arrayPart, null);
        arrayLen = 0;
        mapPart.clear();
    }

    @Override
    public int size() {
        int count = 0;
        for (int i = 0; i < arrayLen; i++) {
            if (arrayPart[i] != null) count++;
        }
        return count + mapPart.size();
    }

    @Override
    public boolean isEmpty() {
        if (!mapPart.isEmpty()) return false;
        for (int i = 0; i < arrayLen; i++) {
            if (arrayPart[i] != null) return false;
        }
        return true;
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        Set<Map.Entry<Object, Object>> set = new LinkedHashSet<>();
        for (int i = 0; i < arrayLen; i++) {
            if (arrayPart[i] != null) {
                set.add(new AbstractMap.SimpleEntry<>(se.krka.kahlua.vm.KahluaUtil.toDouble(i + 1), arrayPart[i]));
            }
        }
        set.addAll(mapPart.entrySet());
        return set;
    }

    @Override
    public Set<Object> keySet() {
        Set<Object> set = new LinkedHashSet<>();
        for (int i = 0; i < arrayLen; i++) {
            if (arrayPart[i] != null) {
                set.add(se.krka.kahlua.vm.KahluaUtil.toDouble(i + 1));
            }
        }
        set.addAll(mapPart.keySet());
        return set;
    }
}
