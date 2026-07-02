package com.example.eboneadminpanel

data class FCMNotification(

    val title: String = "",

    val body: String = ""

)

data class FCMMessage(

    val token: String = "",

    val notification:
    FCMNotification =
        FCMNotification()

)