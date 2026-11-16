package com.virtualization.servicevirtualizationplatform.model;

public record StateAction(
        StateOp op,
        String collection,
        String keyExpr,
        Object valueExpr
) {}
