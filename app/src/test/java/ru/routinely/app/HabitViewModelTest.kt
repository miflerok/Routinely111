package ru.routinely.app

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.routinely.app.data.HabitRepository
import ru.routinely.app.model.Habit
import ru.routinely.app.viewmodel.HabitViewModel
import ru.routinely.app.viewmodel.NotificationEvent
import ru.routinely.app.utils.HabitFilter
import ru.routinely.app.utils.SortOrder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModelTest {

    @get:Rule
    val instantRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val habitDao = FakeHabitDao()
    private val repository = HabitRepository(habitDao)
    private val userPreferencesRepository = TestUserPreferencesRepository()

    private fun createViewModel() = HabitViewModel(repository, userPreferencesRepository)

    /**
     * Сохранение привычки с указанным временем уведомления должно отправить единственный эвент
     * планирования с данными привычки, чтобы UI мог запросить установку будильника.
     */
    @Test
    fun `saveHabit emits schedule event when notification time is present`() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<NotificationEvent>()
        val owner = TestLifecycleOwner()
        viewModel.notificationEvent.observe(owner) { events.add(it) }

        viewModel.saveHabit(
            Habit(
                name = "Drink Water",
                type = "daily",
                notificationTime = "08:00"
            )
        )

        advanceUntilIdle()

        val event = events.single() as NotificationEvent.Schedule
        assertEquals("Drink Water", event.habit.name)
        assertNotNull(event.habit.id)
    }

    /**
     * Сохранение привычки без времени уведомления должно отправлять эвент отмены, чтобы убрать любой
     * ранее запланированный будильник для этой привычки.
     */
    @Test
    fun `saveHabit emits cancel event when notification is absent`() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<NotificationEvent>()
        val owner = TestLifecycleOwner()
        viewModel.notificationEvent.observe(owner) { events.add(it) }

        viewModel.saveHabit(
            Habit(
                name = "Read",
                type = "daily",
                notificationTime = null
            )
        )

        advanceUntilIdle()

        val event = events.single() as NotificationEvent.Cancel
        assertEquals(1, event.habitId)
    }

    /**
     * Пометка привычки как выполненной должна увеличить прогресс, обновить серийности и добавить
     * запись о выполнении в историю.
     */
    @Test
    fun `onHabitCheckedChanged updates progress streak and history`() = runTest {
        val existing = Habit(id = 5, name = "Run", type = "daily", targetValue = 1, currentValue = 0)
        habitDao.insertHabit(existing)
        val viewModel = createViewModel()

        viewModel.onHabitCheckedChanged(existing, isCompleted = true)
        advanceUntilIdle()

        val updated = habitDao.latestHabits().first { it.id == existing.id }
        assertEquals(1, updated.currentValue)
        assertEquals(1, updated.currentStreak)
        assertEquals(1, updated.bestStreak)
        assertNotNull(updated.lastCompletedDate)

        val completions = repository.allCompletions.first()
        assertEquals(1, completions.size)
        assertEquals(existing.id, completions.first().habitId)
    }

    /**
     * Очистка данных должна удалить все сохранённые привычки и связанные с ними записи о выполнениях.
     */
    @Test
    fun `clearAllData removes habits and completions`() = runTest {
        val habit = Habit(id = 7, name = "Stretch", type = "daily", targetValue = 1, currentValue = 1)
        habitDao.insertHabit(habit)
        repository.addCompletion(
            ru.routinely.app.model.HabitCompletion(
                habitId = habit.id,
                completionDay = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis()
            )
        )
        val viewModel = createViewModel()

        viewModel.clearAllData()
        advanceUntilIdle()

        assertEquals(0, habitDao.latestHabits().size)
        assertEquals(0, habitDao.latestCompletions().size)
    }

    /**
     * Состояние экрана статистики должно считать общее количество привычек, лучшие серии и процент
     * выполнений за неделю/месяц на основе сохранённых привычек и истории выполнений.
     */
    @Test
    fun `statsUiState calculates weekly and monthly percentages`() = runTest {
        val today = LocalDate.now()
        val creationDate = today.minusDays(1)

        val habitA = Habit(
            id = 1,
            name = "Meditate",
            type = "daily",
            creationDate = startOfDayMillis(creationDate),
            bestStreak = 2
        )
        val habitB = Habit(
            id = 2,
            name = "Walk",
            type = "daily",
            creationDate = startOfDayMillis(creationDate)
        )

        habitDao.insertHabit(habitA)
        habitDao.insertHabit(habitB)

        repository.addCompletion(
            ru.routinely.app.model.HabitCompletion(
                habitId = habitA.id,
                completionDay = startOfDayMillis(today.minusDays(1)),
                completedAt = System.currentTimeMillis()
            )
        )
        repository.addCompletion(
            ru.routinely.app.model.HabitCompletion(
                habitId = habitB.id,
                completionDay = startOfDayMillis(today.minusDays(1)),
                completedAt = System.currentTimeMillis()
            )
        )
        repository.addCompletion(
            ru.routinely.app.model.HabitCompletion(
                habitId = habitA.id,
                completionDay = startOfDayMillis(today),
                completedAt = System.currentTimeMillis()
            )
        )

        val viewModel = createViewModel()

        advanceUntilIdle()
        val state = viewModel.statsUiState.first { !it.isLoading }

        val calculationMethod = HabitViewModel::class.java.getDeclaredMethod(
            "calculateAccuratePercentage",
            List::class.java,
            List::class.java,
            LocalDate::class.java,
            LocalDate::class.java
        ).apply { isAccessible = true }

        val habits = repository.allHabits.first()
        val completions = repository.allCompletions.first()
        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val endOfWeek = startOfWeek.plusDays(6)
        val expectedCalendarWeek = calculationMethod.invoke(
            viewModel,
            habits,
            completions,
            startOfWeek,
            endOfWeek
        ) as Int
        val expectedRollingWeek = calculationMethod.invoke(
            viewModel,
            habits,
            completions,
            today.minusDays(6),
            today
        ) as Int
        val endOfMonth = today.withDayOfMonth(1).plusMonths(1).minusDays(1)
        val expectedMonthly = calculationMethod.invoke(
            viewModel,
            habits,
            completions,
            today.withDayOfMonth(1),
            endOfMonth
        ) as Int

        assertEquals(2, state.totalHabitsCount)
        assertEquals(2, state.bestStreakOverall)
        assertEquals(expectedCalendarWeek, state.weeklyCompletionPercentage)
        assertEquals(expectedRollingWeek, state.rollingWeeklyCompletionPercentage)
        assertEquals(expectedMonthly, state.monthlyCompletionPercentage)

        val recentTrend = state.weeklyTrend.takeLast(2)
        assertEquals(1f, recentTrend[0].completionRatio, 0.001f)
        assertEquals(0.5f, recentTrend[1].completionRatio, 0.001f)
    }

    /**
     * Выбор даты на экране статистики должен обновлять выделение в календаре и показывать только
     * привычки, закрытые в выбранный день.
     */
    @Test
    fun `onStatsDateSelected updates calendar selection and habits`() = runTest {
        val today = LocalDate.now()
        val creationDate = today.minusDays(2)
        val targetDate = today.minusDays(1)

        val reading = Habit(
            id = 10,
            name = "Read",
            type = "daily",
            creationDate = startOfDayMillis(creationDate)
        )
        val yoga = Habit(
            id = 11,
            name = "Yoga",
            type = "daily",
            creationDate = startOfDayMillis(creationDate)
        )

        habitDao.insertHabit(reading)
        habitDao.insertHabit(yoga)

        repository.addCompletion(
            ru.routinely.app.model.HabitCompletion(
                habitId = reading.id,
                completionDay = startOfDayMillis(targetDate),
                completedAt = System.currentTimeMillis()
            )
        )
        repository.addCompletion(
            ru.routinely.app.model.HabitCompletion(
                habitId = yoga.id,
                completionDay = startOfDayMillis(today),
                completedAt = System.currentTimeMillis()
            )
        )

        val viewModel = createViewModel()

        viewModel.onStatsDateSelected(targetDate)
        advanceUntilIdle()

        val state = viewModel.statsUiState.first { !it.isLoading && it.selectedDate == targetDate }

        assertEquals(2, state.selectedDateHabits.size)
        val readingSnapshot = state.selectedDateHabits.first { it.habit.id == reading.id }
        assertTrue(readingSnapshot.isCompleted)
        val selectedDay = state.calendarDays.first { it.date == targetDate }
        assertEquals(true, selectedDay.isSelected)
        assertEquals(true, selectedDay.isCompleted)
    }

    /**
     * Привычка не должна появляться в статистике до даты своего создания.
     */
    @Test
    fun `stats exclude habits before creation date`() = runTest {
        val today = LocalDate.now()
        val targetDate = today.minusDays(1)

        val habit = Habit(
            id = 12,
            name = "Meditate",
            type = "daily",
            creationDate = startOfDayMillis(today)
        )

        habitDao.insertHabit(habit)

        val viewModel = createViewModel()

        viewModel.onStatsDateSelected(targetDate)
        advanceUntilIdle()

        val state = viewModel.statsUiState.first { !it.isLoading && it.selectedDate == targetDate }

        assertTrue(state.selectedDateHabits.isEmpty())
    }

    /**
     * Двойной выбор сортировки по имени должен менять направление сортировки, сохраняя выбранный
     * режим сортировки.
     */
    @Test
    fun `setSortOrder toggles alphabetical direction on repeated selection`() = runTest {
        val viewModel = createViewModel()

        viewModel.setSortOrder(SortOrder.BY_NAME)
        advanceUntilIdle()
        val ascendingState = viewModel.uiState.value

        viewModel.setSortOrder(SortOrder.BY_NAME)
        advanceUntilIdle()
        val descendingState = viewModel.uiState.value

        assertEquals(SortOrder.BY_NAME, ascendingState.sortOrder)
        assertTrue(ascendingState.isNameSortAsc)
        assertEquals(SortOrder.BY_NAME, descendingState.sortOrder)
        assertEquals(false, descendingState.isNameSortAsc)
    }

    /**
     * Смена режима фильтрации должна сбрасывать ранее выбранный фильтр по категории.
     */
    @Test
    fun `setFilter clears category filter when changing mode`() = runTest {
        val viewModel = createViewModel()

        viewModel.setCategoryFilter("Health")
        advanceUntilIdle()

        viewModel.setFilter(HabitFilter.TODAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(HabitFilter.TODAY, state.habitFilter)
        assertNull(state.categoryFilter)
    }

    /**
     * Обновление прогресса до целевого значения должно отмечать привычку выполненной, создавать
     * запись о выполнении и увеличивать счётчики серий.
     */
    @Test
    fun `updateHabitProgress saves completion and streak when reaching target`() = runTest {
        val habit = Habit(id = 20, name = "Pushups", type = "daily", targetValue = 10, currentValue = 5)
        habitDao.insertHabit(habit)
        val viewModel = createViewModel()

        viewModel.updateHabitProgress(habit, newValue = 10)
        advanceUntilIdle()

        val updated = habitDao.latestHabits().first { it.id == habit.id }
        val completions = repository.allCompletions.first()

        assertEquals(10, updated.currentValue)
        assertEquals(1, updated.currentStreak)
        assertEquals(1, completions.size)
        assertEquals(habit.id, completions.first().habitId)
    }

    /**
     * Удаление привычки должно убрать её из хранения и отправить эвент отмены для очистки
     * запланированных уведомлений.
     */
    @Test
    fun `deleteHabit cancels notification and removes stored habit`() = runTest {
        val habit = Habit(id = 30, name = "Journal", type = "daily")
        habitDao.insertHabit(habit)
        val viewModel = createViewModel()

        val events = mutableListOf<NotificationEvent>()
        val owner = TestLifecycleOwner()
        viewModel.notificationEvent.observe(owner) { events.add(it) }

        viewModel.deleteHabit(habit)
        advanceUntilIdle()

        val stored = habitDao.latestHabits().first { it.id == habit.id }
        assertTrue(stored.isArchived)
        val cancelEvent = events.single() as NotificationEvent.Cancel
        assertEquals(habit.id, cancelEvent.habitId)
    }

    private fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle
            get() = registry
    }
}
