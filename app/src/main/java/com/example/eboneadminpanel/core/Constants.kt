package com.example.eboneadminpanel.core

object Constants {

    const val ROLE_SUPER_ADMIN = "super_admin"
    const val ROLE_COMPANY_ADMIN = "company_admin"
    const val ROLE_MANAGER = "manager"
    const val ROLE_SUPERVISOR = "supervisor"
    const val ROLE_EMPLOYEE = "employee"

    const val LICENSE_ACTIVE = "active"
    const val LICENSE_EXPIRED = "expired"
    const val LICENSE_SUSPENDED = "suspended"

    const val PREF_NAME = "ebone_session_prefs"
    const val KEY_USER_ROLE = "user_role"
    const val KEY_USER_ID = "user_id"
    const val KEY_COMPANY_ID = "company_id"
    const val KEY_IS_LOGGED_IN = "is_logged_in"

    val CITY_PREFIXES = mapOf(
        "Lahore" to "LHR",
        "Karachi" to "KHI",
        "Islamabad" to "ISB",
        "Rawalpindi" to "RWP",
        "Faisalabad" to "FSD",
        "Multan" to "MUL",
        "Peshawar" to "PSH",
        "Gujranwala" to "GRW",
        "Sialkot" to "SKT",
        "Hyderabad" to "HYD",
        "Quetta" to "QTA",
        "Sargodha" to "SGD"
    )

    const val EXTRA_COMPANY_ID = "extra_company_id"
    const val EXTRA_COMPANY_NAME = "extra_company_name"
}