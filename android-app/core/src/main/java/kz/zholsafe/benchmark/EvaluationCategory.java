package kz.zholsafe.benchmark;

/**
 * Evaluation subsets for model comparison. Results are reported per category; no accuracy claim
 * may be made for a category without labelled data in that category.
 */
public enum EvaluationCategory {
    LATENCY_ONLY,
    DAY,
    DUSK,
    NIGHT,
    DISTANT_OBJECT,
    SMALL_ANIMAL,
    LARGE_LIVESTOCK
}
