package com.example.data

import kotlinx.coroutines.flow.Flow

class EventRepository(private val eventDao: EventDao) {
    val allEvents: Flow<List<Event>> = eventDao.getAllEvents()

    fun getEventByIdFlow(id: Int): Flow<Event?> = eventDao.getEventByIdFlow(id)

    suspend fun getEventById(id: Int): Event? = eventDao.getEventById(id)

    suspend fun insert(event: Event): Long = eventDao.insertEvent(event)

    suspend fun update(event: Event) = eventDao.updateEvent(event)

    suspend fun delete(event: Event) = eventDao.deleteEvent(event)

    suspend fun deleteById(id: Int) = eventDao.deleteEventById(id)

    suspend fun getAllEventsList(): List<Event> = eventDao.getAllEventsList()
}
