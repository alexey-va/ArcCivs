package ru.arc.civs

import java.util.Locale
import java.util.UUID

/** Names are chosen once; persisted residents keep their original names. */
object CivsNames {
    private val male = """
        Агафон Аким Александр Алексей Анатолий Андрей Антон Аркадий Арсений Артём
        Афанасий Богдан Борис Вадим Валентин Валерий Василий Виктор Виталий Владимир
        Владислав Всеволод Вячеслав Гавриил Геннадий Георгий Герман Глеб Григорий Данила
        Демид Денис Дмитрий Добрыня Егор Елисей Емельян Еремей Ефим Захар Иван Игнат
        Игорь Илья Кирилл Клим Кондрат Константин Корней Кузьма Лаврентий Леонид Лука
        Макар Максим Матвей Мирон Михаил Назар Никита Николай Олег Павел Пётр Платон
        Прохор Родион Роман Ростислав Савва Савелий Святослав Семён Сергей Станислав
        Степан Тарас Тимофей Тихон Фёдор Филипп Фома Юрий Яков Ярослав
    """.trimIndent().split(Regex("\\s+"))
    private val female = """
        Агафья Аглая Агния Аксинья Александра Алёна Алина Алиса Алла Анастасия Ангелина
        Анна Антонина Арина Ася Белла Богдана Валентина Валерия Варвара Василиса Вера
        Вероника Виктория Влада Галина Дарья Евгения Евдокия Екатерина Елена Елизавета
        Есения Жанна Зинаида Злата Зоя Инга Инна Ирина Кира Клавдия Ксения Лада Лариса
        Лидия Лилия Любовь Людмила Майя Маргарита Марина Мария Марта Милана Милена
        Мирослава Надежда Наталья Нелли Нина Оксана Олеся Ольга Пелагея Полина Прасковья
        Раиса Регина Римма Роза Руслана Светлана Серафима Софья Таисия Тамара Татьяна
        Ульяна Фаина Феврония Флора Христина Элина Юлия Яна Ярослава
    """.trimIndent().split(Regex("\\s+"))

    fun name(city: Settlement, workerId: UUID, sex: CivSex): String {
        city.workers.firstOrNull { it.id == workerId }?.let { return it.name }
        val pool = if (sex == CivSex.MALE) male else female
        val used = city.workers.mapTo(hashSetOf()) { it.name.trim().lowercase(Locale.ROOT) }
        val start = Math.floorMod(workerId.hashCode(), pool.size)
        for (step in pool.indices) {
            val candidate = pool[(start + step) % pool.size]
            if (candidate.lowercase(Locale.ROOT) !in used) return candidate
        }
        // Only oversized legacy towns can exhaust a whole pool.
        val base = pool[start]
        return generateSequence(2) { it + 1 }.map { "$base $it" }
            .first { it.lowercase(Locale.ROOT) !in used }
    }
}
