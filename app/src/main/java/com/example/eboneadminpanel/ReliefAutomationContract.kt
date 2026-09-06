package com.example.eboneadminpanel

/**
 * Central contract for the Relief Automation system.
 *
 * IMPORTANT:
 * This file only defines the states and job information.
 * It does NOT perform any ISP action.
 *
 * ISP execution (EBONE / WATEEN / ZONG) will be connected
 * in the next phases after the queue/state system is stable.
 */
object ReliefAutomationContract {

    const val COLLECTION_DISABLE_JOBS = "reliefDisableJobs"

    const val STATUS_QUEUED = "QUEUED"
    const val STATUS_PROCESSING = "PROCESSING"
    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_FAILED = "FAILED"

    const val ACTION_DISABLE = "DISABLE"

    const val ISP_EBONE = "EBONE"
    const val ISP_WATEEN = "WATEEN"
    const val ISP_ZONG = "ZONG"

    const val CUSTOMER_RELIEF_ACTIVE = "ACTIVE"
    const val CUSTOMER_RELIEF_ACTIVE_PENDING = "ACTIVE_PENDING"
    const val CUSTOMER_RELIEF_DUE = "DUE"
    const val CUSTOMER_RELIEF_DISABLED = "DISABLED"
    const val CUSTOMER_RELIEF_CLEARED = "CLEARED"

    const val FIELD_CUSTOMER_ID = "customerId"
    const val FIELD_COMPANY = "company"
    const val FIELD_ACTION = "action"
    const val FIELD_STATUS = "status"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_UPDATED_AT = "updatedAt"
    const val FIELD_ATTEMPTS = "attempts"
    const val FIELD_LAST_ERROR = "lastError"
    const val FIELD_STARTED_AT = "startedAt"
    const val FIELD_COMPLETED_AT = "completedAt"
    const val FIELD_GRACE_DEADLINE = "graceDeadline"
}