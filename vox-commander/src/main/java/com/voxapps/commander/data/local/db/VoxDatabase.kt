package com.voxapps.commander.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.domain.intent.model.FastMapRule
import com.voxapps.commander.utils.fromJsonOrNull

@Database(entities = [FastMapRule::class], version = 13)
@TypeConverters(StringListConverter::class, StringListListConverter::class)
abstract class VoxDatabase : RoomDatabase() {
    abstract fun fastMapDao(): FastMapDao
}

class StringListConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        if (value == null) return "[]"
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> =
        gson.fromJsonOrNull<List<String>>(value) ?: emptyList()
}

class StringListListConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromStringListList(value: List<List<String>>?): String {
        if (value == null) return "[]"
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringListList(value: String?): List<List<String>> =
        gson.fromJsonOrNull<List<List<String>>>(value) ?: emptyList()
}