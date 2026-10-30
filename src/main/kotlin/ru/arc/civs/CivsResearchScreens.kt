package ru.arc.civs

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.civs.CivsDialogPresentation.Action
import ru.arc.paper.menu.DialogTables.PanelSkin
import java.util.Locale
import java.util.UUID
import ru.arc.civs.CivsUi.Companion.PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.WHITE
import ru.arc.civs.CivsUi.Companion.LAND
import ru.arc.civs.CivsUi.Companion.COMMIT
import ru.arc.civs.CivsUi.Companion.PERSONAL
import ru.arc.civs.CivsUi.Companion.STOCK

internal fun CivsUi.showResearch(player: Player, cityId: UUID, error: Component? = null) {
    val view = try { host.research(player, cityId) } catch (failure: Exception) {
        present(player, "civs.research", "research.title", listOf(body(text.failure(player, failure))), emptyList(),
            reopen = { showResearch(player, cityId) }, cityId = cityId)
        return
    }
    val viewedCity = selectedCity(player, cityId) ?: return
    present(player, "civs.research", "research.title", listOfNotNull(
        body(tint(text.literal(viewedCity.name), LAND)),
        presentation.summary(Component.join(Component.newline(), buildList {
            add(tr(player, "research.completed-count", "count" to view.completed.size, "total" to CivResearchId.entries.size))
            view.project?.let { project ->
                add(tint(researchName(player, project.research), PERSONAL))
                add(researchProgress(player, project))
                add(researchStatus(player, view, project.research))
            } ?: add(tr(player, when {
                view.completed.containsAll(CivResearchId.entries) -> "research.all-complete"
                view.autonomous -> "research.auto-idle"
                else -> "research.idle"
            }))
        })),
        presentation.panel(Component.join(Component.newline(), listOfNotNull(
            tr(player, if (view.autonomous) "research.auto-help" else "research.work-help"),
            if (view.universityId == null) tr(player, "reason.university-required") else null,
            if (!canManageSelected(player, viewedCity)) tr(player, "research.read-only") else null,
        )), PanelSkin.NAVY),
        error?.let(::body),
    ), buildList {
        CivResearchBranch.entries.forEach { branch ->
            val ids = CivResearchId.entries.filter { CivsResearch.branch(it) == branch }
            add(valueButton(player, "research_branch_${branch.name.lowercase(Locale.ROOT)}", "common.destination",
                tr(player, "research.branch.${branch.name.lowercase(Locale.ROOT)}"), PERSONAL,
                tr(player, "research.branch-progress", "count" to ids.count { it in view.completed }, "total" to ids.size)) {
                showResearchBranch(player, cityId, branch)
            })
        }
        add(button(player, "industry", "industry.open", STOCK) { showIndustry(player, cityId) })
    }, reopen = { showResearch(player, cityId) }, cityId = cityId)
}


internal fun CivsUi.showResearchBranch(player: Player, cityId: UUID, branch: CivResearchBranch) {
    val view = try { host.research(player, cityId) } catch (failure: Exception) {
        return showResearch(player, cityId, text.failure(player, failure))
    }
    val city = selectedCity(player, cityId) ?: return
    val ids = CivResearchId.entries.filter { CivsResearch.branch(it) == branch }
    present(player, "civs.research-branch", "research.title", listOf(
        body(tint(text.literal(city.name), LAND)),
        presentation.summary(Component.join(Component.newline(), listOf(
            tint(tr(player, "research.branch.${branch.name.lowercase(Locale.ROOT)}"), PERSONAL),
            tr(player, "research.branch-progress", "count" to ids.count { it in view.completed }, "total" to ids.size),
        ))),
    ), buildList {
        ids.forEach { id ->
            add(valueButton(player, "research_${id.name.lowercase(Locale.ROOT)}", "common.destination", researchName(player, id),
                if (id in view.completed) COMMIT else PERSONAL,
                researchStatus(player, view, id).append(Component.newline()).append(tr(player, "research.effect.${id.name.lowercase(Locale.ROOT)}"))) {
                showResearchDetail(player, cityId, id)
            })
        }
    }, reopen = { showResearchBranch(player, cityId, branch) }, cityId = cityId)
}


internal fun CivsUi.showResearchDetail(player: Player, cityId: UUID, research: CivResearchId, error: Component? = null) {
    val view = try { host.research(player, cityId) } catch (failure: Exception) {
        return showResearch(player, cityId, text.failure(player, failure))
    }
    val viewedCity = selectedCity(player, cityId) ?: return
    val offer = view.offers.firstOrNull { it.research == research }
    val project = view.project?.takeIf { it.research == research }
    val cost = project?.cost ?: offer?.cost
    val prerequisites = CivsResearch.prerequisites(research)
    val canManage = canManageSelected(player, viewedCity)
    present(player, "civs.research-detail", "research.detail-title", listOfNotNull(
        body(tint(text.literal(viewedCity.name), LAND)),
        presentation.summary(Component.join(Component.newline(), listOfNotNull(
            tint(researchName(player, research), PERSONAL), researchStatus(player, view, research),
            project?.let { researchProgress(player, it) },
            tr(player, "research.effect.${research.name.lowercase(Locale.ROOT)}"),
        ))),
        presentation.panel(Component.join(Component.newline(), listOfNotNull(
            if (prerequisites.isNotEmpty()) tr(player, "research.prerequisites", "names" to
                Component.join(text.literal(" · "), prerequisites.map { tint(researchName(player, it), PERSONAL) })) else null,
            Component.join(Component.newline(), listOf(tr(player, "research.required-buildings")) +
                (mapOf(BuildingType.UNIVERSITY to CivsResearch.universityLevel(research)) +
                    CivsResearch.buildingRequirements(research)).map { (type, level) ->
                    val ready = if (type == BuildingType.UNIVERSITY)
                        CivsResearch.university(viewedCity)?.level?.let { it >= level } == true
                    else viewedCity.buildings.any { it.type == type && it.phase == BuildingPhase.ACTIVE && it.level >= level }
                    Component.text(if (ready) "✔ " else "✘ ", if (ready) COMMIT else STOCK)
                        .append(buildingName(player, type, level))
                }),
        )), PanelSkin.NAVY),
        listOfNotNull(
            if (project == null && research !in view.completed && offer != null)
                tr(player, "research.duration", "sessions" to tint(text.literal(offer.workRequired), STOCK)) else null,
            if (research !in view.completed && cost != null)
                tint(tr(player, if (project?.funded == true) "research.cost-paid" else "research.cost"), STOCK) else null,
            if (research !in view.completed && cost != null) researchCost(player, cost) else null,
            project?.let { tr(player, when {
                it.paused -> "research.pause-help"
                it.funded -> "research.funded-help"
                view.autonomous -> "research.auto-funding-help"
                else -> "research.native-funding-help"
            }) },
            offer?.reason?.takeIf { project == null && research !in view.completed &&
                it !in setOf("research-prerequisites", "university-level-required") }?.let { text.reason(player, it) },
            if (!canManage) tr(player, "research.read-only") else null,
        ).takeIf { it.isNotEmpty() }?.let { presentation.panel(Component.join(Component.newline(), it), PanelSkin.NAVY) },
        error?.let(::body),
    ), buildList {
        if (canManage && offer?.available == true && view.project == null) add(button(player, "start", "research.start", COMMIT, appearance = Action.START) {
            if (selectedCity(player, cityId)?.let { canManageSelected(player, it) } != true)
                showResearchDetail(player, cityId, research, text.reason(player, "not-authorized"))
            else perform(player, "civs.research-detail", "research.detail-title", { host.beginResearch(player, cityId, research) },
                success = { showResearchDetail(player, cityId, research) }, failure = { showResearchDetail(player, cityId, research, it) })
        })
        if (canManage && project != null) add(button(player, "pause", if (project.paused) "research.resume" else "research.pause",
            if (project.paused) COMMIT else STOCK, appearance = if (project.paused) Action.START else null) {
            if (selectedCity(player, cityId)?.let { canManageSelected(player, it) } != true)
                showResearchDetail(player, cityId, research, text.reason(player, "not-authorized"))
            else perform(player, "civs.research-detail", "research.detail-title",
                { host.setResearchPaused(player, cityId, project.id, !project.paused) },
                success = { showResearchDetail(player, cityId, research) }, failure = { showResearchDetail(player, cityId, research, it) })
        })
        if (!view.autonomous && canManage && view.universityId != null)
            add(button(player, "university", "research.university-open", LAND) { showBuilding(player, view.universityId) })
    }, reopen = { showResearchDetail(player, cityId, research) }, cityId = cityId)
}


internal fun CivsUi.showIndustry(player: Player, cityId: UUID, page: Int = 0, error: Component? = null) {
    val view = try { host.industry(player, cityId) } catch (failure: Exception) {
        present(player, "civs.industry", "industry.title", listOf(body(text.failure(player, failure))), emptyList(),
            reopen = { showIndustry(player, cityId, page) }, cityId = cityId)
        return
    }
    val viewedCity = selectedCity(player, cityId) ?: return
    val current = page.coerceIn(0, lastPage(view.buildings.size))
    present(player, "civs.industry", "industry.title", listOfNotNull(
        body(tint(text.literal(viewedCity.name), LAND)),
        presentation.paragraph(tr(player, if (view.autonomous) "industry.auto-help" else "industry.native-help")),
        if (view.buildings.isEmpty()) body(player, "industry.empty") else null,
        error?.let(::body),
    ), buildList {
        view.buildings.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
            val level = viewedCity.building(building.buildingId)?.level ?: 1
            val recipe = building.recipes.firstOrNull { it.id == building.activeRecipe }
            add(valueButton(player, "industry_$index", "common.destination", buildingName(player, building.type, level),
                buildingColor(building.type), recipe?.let { industryRecipe(player, view.autonomous, it) } ?: tr(player, "industry.no-recipe")) {
                showIndustryBuilding(player, cityId, building.buildingId)
            })
        }
        pages(player, current, view.buildings.size) { showIndustry(player, cityId, it) }
        add(button(player, "research", "research.open", PERSONAL) { showResearch(player, cityId) })
    }, reopen = { showIndustry(player, cityId, current) }, cityId = cityId)
}


internal fun CivsUi.showIndustryBuilding(player: Player, cityId: UUID, buildingId: UUID, error: Component? = null) {
    val view = try { host.industry(player, cityId) } catch (failure: Exception) {
        return showIndustry(player, cityId, error = text.failure(player, failure))
    }
    val viewedCity = selectedCity(player, cityId) ?: return
    val building = view.buildings.firstOrNull { it.buildingId == buildingId } ?: return showIndustry(player, cityId)
    val nativeBuilding = viewedCity.building(buildingId) ?: return showIndustry(player, cityId)
    val canChoose = !view.autonomous && canManageSelected(player, viewedCity) && !host.cityBusy(player) &&
        viewedCity.construction?.buildingId != buildingId && nativeBuilding.phase == BuildingPhase.ACTIVE
    present(player, "civs.industry-building", "industry.building-title", listOfNotNull(
        body(tint(text.literal(viewedCity.name), LAND).append(Component.newline())
            .append(buildingName(player, building.type, nativeBuilding.level))),
        presentation.paragraph(tr(player, if (view.autonomous) "industry.auto-help" else "industry.native-help")),
        building.waitingReason?.let { body(text.reason(player, it)) },
        if (building.recipes.isEmpty()) body(player, "industry.no-recipe") else null,
        if (building.recipes.isNotEmpty()) presentation.paragraph(Component.join(Component.newline(), buildList {
            building.recipes.firstOrNull { it.id == building.activeRecipe }?.let { recipe ->
                add(tint(tr(player, "industry.active-recipe", "item" to industryOutput(player, view.autonomous, recipe.output)), COMMIT))
            }
            building.recipes.forEach { recipe -> add(tint(industryRecipe(player, view.autonomous, recipe),
                if (recipe.id == building.activeRecipe) COMMIT else STOCK)) }
        })) else null,
        if (building.type in setOf(BuildingType.SMELTERY, BuildingType.CRUSHER)) presentation.paragraph(
            tr(player, if (building.type == BuildingType.CRUSHER) "industry.crusher-gates"
                else if (view.autonomous) "industry.auto-smeltery-gates" else "industry.smeltery-gates")
                .append(Component.newline()).append(tr(player, "industry.level-gates"))) else null,
        error?.let(::body),
    ), buildList {
        if (canChoose) building.choices.forEachIndexed { index, choice ->
            val recipe = building.recipes.firstOrNull { it.id == choice || it.output == choice }
            add(valueButton(player, "production_$index", if (choice == building.production) "common.selected" else "common.choice",
                industryOutput(player, false, recipe?.output ?: choice), if (choice == building.production) COMMIT else WHITE,
                recipe?.let { industryRecipe(player, false, it) } ?: Component.empty()) {
                if (selectedCity(player, cityId)?.let { canManageSelected(player, it) } != true)
                    showIndustryBuilding(player, cityId, buildingId, text.reason(player, "not-authorized"))
                else if (choice == building.production) showIndustryBuilding(player, cityId, buildingId)
                else perform(player, "civs.industry-building", "industry.building-title",
                    { host.setProduction(player, buildingId, choice) }, success = { showIndustryBuilding(player, cityId, buildingId) },
                    failure = { showIndustryBuilding(player, cityId, buildingId, it) })
            })
        }
        add(button(player, "research", "research.open", PERSONAL) { showResearch(player, cityId) })
    }, reopen = { showIndustryBuilding(player, cityId, buildingId) }, cityId = cityId)
}


internal fun CivsUi.selectedCity(player: Player, cityId: UUID): Settlement? =
    host.settlements().firstOrNull { it.id == cityId } ?: host.city(player)?.takeIf { it.id == cityId }


internal fun CivsUi.canManageSelected(player: Player, city: Settlement): Boolean =
    if (city.autonomy != null) isAdmin(player) else city.canManage(player.uniqueId)


internal fun CivsUi.researchName(player: Player, id: CivResearchId): Component = tr(player, "research.name.${id.name.lowercase(Locale.ROOT)}")

internal fun CivsUi.industryProductName(player: Player, product: CivIndustryProduct): Component = tr(player, "industry.product.${product.name.lowercase(Locale.ROOT)}")

internal fun CivsUi.researchProgress(player: Player, project: CivResearchProject): Component =
    tr(player, "research.progress", "done" to project.workDone, "total" to project.workRequired)

internal fun CivsUi.researchStatus(player: Player, view: CivResearchView, id: CivResearchId): Component {
    val project = view.project?.takeIf { it.research == id }
    val key = when {
        id in view.completed -> "completed"
        project?.paused == true -> "paused"
        project?.funded == false -> "waiting-materials"
        project != null -> "in-progress"
        view.offers.any { it.research == id && it.available } -> "available"
        else -> "locked"
    }
    return tint(tr(player, "research.state.$key"), if (key in setOf("completed", "in-progress")) COMMIT else STOCK)
}


internal fun CivsUi.researchCost(player: Player, cost: CivResearchCost): Component = Component.join(text.literal(" · "), buildList {
    Resource.entries.filter { cost.stock[it] > 0 }.forEach { resource ->
        add(tr(player, "industry.amount", "item" to text.resource(player, resource), "amount" to tint(text.literal(cost.stock[resource]), STOCK)))
    }
    cost.products.forEach { (product, count) -> add(tr(player, "industry.amount", "item" to industryProductName(player, product), "amount" to tint(text.literal(count), STOCK))) }
    cost.materials.forEach { (material, count) -> add(tr(player, "industry.amount", "item" to materialName(player, material), "amount" to tint(text.literal(count), STOCK))) }
})


internal fun CivsUi.industryOutput(player: Player, autonomous: Boolean, output: String): Component =
    if (autonomous) CivIndustryProduct.entries.firstOrNull { it.name == output }?.let { industryProductName(player, it) }
        ?: materialName(player, output) else materialName(player, output)


internal fun CivsUi.industryRecipe(player: Player, autonomous: Boolean, recipe: CivIndustryRecipeView): Component {
    val inputs = buildList {
        Resource.entries.filter { recipe.inputStock[it] > 0 }.forEach { resource ->
            add(tr(player, "industry.amount", "item" to text.resource(player, resource), "amount" to recipe.inputStock[resource]))
        }
        recipe.inputProducts.forEach { (product, count) -> add(tr(player, "industry.amount", "item" to industryProductName(player, product), "amount" to tint(text.literal(count), STOCK))) }
        recipe.inputs.forEach { (material, count) -> add(tr(player, "industry.amount", "item" to materialName(player, material), "amount" to tint(text.literal(count), STOCK))) }
    }
    return tr(player, "industry.chain", "inputs" to if (inputs.isEmpty()) tr(player, "industry.gathering")
        else Component.join(text.literal(" + "), inputs), "output" to industryOutput(player, autonomous, recipe.output), "amount" to recipe.outputAmount)
}
