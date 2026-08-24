ALTER TABLE billing_orders DROP CONSTRAINT billing_orders_status_check;
ALTER TABLE billing_orders ALTER COLUMN status TYPE VARCHAR(32);

ALTER TABLE billing_orders ADD CONSTRAINT billing_orders_status_check
    CHECK (status IN (
        'CREATED', 'PROCESSING', 'RECONCILIATION_REQUIRED', 'PENDING',
        'PAID', 'FAILED', 'CANCELED', 'REFUNDED', 'EXPIRED'
    ));

CREATE UNIQUE INDEX uq_billing_single_open_order
    ON billing_orders(account_id, plan_id, provider)
    WHERE status IN ('CREATED', 'PROCESSING', 'RECONCILIATION_REQUIRED', 'PENDING');
