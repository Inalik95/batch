package com.vision.batch.camaroduplicate.model;

/**
 * Delivery frequency of the Camaro "#06 Fusion Tiers T tiers" flow.
 * One job handles both; the type only selects the file pattern and the
 * column mapping (see CamaroDuplicateProperties.formats).
 */
public enum CamaroFileType {
    DAILY,
    MONTHLY
}
