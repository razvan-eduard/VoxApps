package com.voxcommander.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.voxcommander.app.data.local.dao.FastMapDao
import com.voxcommander.app.domain.intent.model.FastMapRule

@Database(entities = [FastMapRule::class], version = 12)
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
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val type = TypeToken.getParameterized(List::class.java, String::class.java).type
            gson.fromJson(value, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class StringListListConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromStringListList(value: List<List<String>>?): String {
        if (value == null) return "[]"
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringListList(value: String?): List<List<String>> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val outerType = TypeToken.getParameterized(
                List::class.java,
                TypeToken.getParameterized(List::class.java, String::class.java).type
            ).type
            gson.fromJson(value, outerType)
        } catch (e: Exception) {
            emptyList()
        }
    }
}