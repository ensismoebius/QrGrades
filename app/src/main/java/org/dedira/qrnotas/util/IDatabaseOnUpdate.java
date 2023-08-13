package org.dedira.qrnotas.util;

public interface IDatabaseOnUpdate<T> {
    void onUpdateComplete(boolean success, T object);
}
