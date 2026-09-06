package com.example.eboneadminpanel

import com.google.firebase.Timestamp

/**
 * Represents one Relief Automation disable job.
 *
 * IMPORTANT:
 * This class does NOT perform the ISP action.
 * It only represents the job that will later be processed
 * by the automation system.
 */
data class ReliefDisableJob(

    val customerId: String = "",

    val company: String = "",

    val action: String = ReliefAutomationContract.ACTION_DISABLE,

    val status: String = ReliefAutomationContract.STATUS_QUEUED,

    val createdAt: Timestamp? = null,

    val updatedAt: Timestamp? = null,

    val attempts: Int = 0,

    val lastError: String = "",

    val startedAt: Timestamp? = null,

    val completedAt: Timestamp? = null,

    val graceDeadline: Timestamp? = null
)