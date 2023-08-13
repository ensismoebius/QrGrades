package org.dedira.qrnotas.util;

public interface IDatabaseOnSave<T> {
    void onSaveComplete(boolean success, T object);
}
