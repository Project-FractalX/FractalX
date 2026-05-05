package org.fractalx.annotations;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedSaga {
    /**
     * Unique saga identifier used to correlate orchestration state.
     * Example: {@code "place-order-saga"}
     */
    String sagaId();

    /**
     * Name of the compensation (rollback) method to call on failure.
     * The method must be in the same class and accept the same parameters.
     */
    String compensationMethod() default "";

    /**
     * Timeout in milliseconds for the entire saga execution.
     */
    long timeout() default 30000;

    /**
     * Ordered step names that this saga executes.
     * Used as a hint for the SagaAnalyzer to validate detected steps against
     * the declared intent. Example: {@code {"create-order", "process-payment", "reserve-inventory"}}
     */
    String[] steps() default {};

    /**
     * Human-readable description of what this saga does.
     * Used in generated documentation and the admin UI.
     */
    String description() default "";

    /**
     * Status value to set on the aggregate entity when the saga completes successfully.
     *
     * <p><b>Required</b> — omitting this causes a compile error.
     *
     * <p>Usage:
     * <ul>
     *   <li>String status field:  {@code successStatus = "ACTIVE"}</li>
     *   <li>Enum status field:    {@code successStatus = "OrderStatus.CONFIRMED"}</li>
     * </ul>
     *
     * <p>The value must match one of the valid status constants defined on your entity.
     * For enum types, prefix with the enum class name (e.g. {@code "MyStatus.DONE"}).
     */
    String successStatus();

    /**
     * Status value to set on the aggregate entity when the saga fails and compensation completes.
     *
     * <p><b>Required</b> — omitting this causes a compile error.
     *
     * <p>Usage:
     * <ul>
     *   <li>String status field:  {@code failureStatus = "CANCELLED"}</li>
     *   <li>Enum status field:    {@code failureStatus = "OrderStatus.CANCELLED"}</li>
     * </ul>
     *
     * <p>The value must match one of the valid status constants defined on your entity.
     * For enum types, prefix with the enum class name (e.g. {@code "MyStatus.FAILED"}).
     */
    String failureStatus();
}