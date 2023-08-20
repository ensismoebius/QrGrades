package org.dedira.qrnotas.util;

public interface IDatabaseOnDelete<T> {
    void onLoadComplete(boolean success, T object);
}
