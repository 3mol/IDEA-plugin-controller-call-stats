package cc.miaooo.prodcallstats.psi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.nullize

/**
 * Resolves Spring MVC handler methods from PSI.
 *
 * Lookup short-circuits on short annotation names first; the FQN check via
 * [JavaPsiFacade.findClass] only runs when the short name matches, keeping the
 * hot path (every controller file open) cheap.
 */
object SpringControllerScanner {

    private val log = Logger.getInstance("ProdCallStats")

    private val CONTROLLER_FQNS = setOf(
        "org.springframework.stereotype.Controller",
        // @RestController moved to the web package in Spring 4; the stereotype
        // path does not exist, so don't look it up there.
        "org.springframework.web.bind.annotation.RestController",
    )

    // method-level annotations -> HTTP method (null means: read `method` attribute)
    private val METHOD_ANNOTATIONS: List<Pair<String, String?>> = listOf(
        "org.springframework.web.bind.annotation.RequestMapping" to null,
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH",
    )

    private const val REQUEST_MAPPING_FQN = "org.springframework.web.bind.annotation.RequestMapping"

    fun isController(cls: PsiClass): Boolean {
        if (cls is PsiCompiledElement) {
            log.warn("[PCS] isController(${cls.name}) -> compiled element, skip")
            return false
        }
        val annotations = collectAnnotations(cls)
        val annInfos = annotations.map { ann ->
            (ann.qualifiedName ?: ann.nameReferenceElement?.text) to (ann.qualifiedName)
        }
        log.warn("[PCS] isController(${cls.qualifiedName ?: cls.name}) annotations=$annInfos")
        val result = annotations.any { ann ->
            val shortName = (ann.qualifiedName ?: ann.nameReferenceElement?.text) ?: return@any false
            val resolved = shortName.substringAfterLast('.')
            CONTROLLER_FQNS.any { it.substringAfterLast('.') == resolved } &&
                resolveFqn(ann, CONTROLLER_FQNS) != null
        }
        log.warn("[PCS] isController(${cls.qualifiedName ?: cls.name}) -> $result")
        return result
    }

    fun resolve(method: PsiMethod): HandlerMethod? {
        val mapping = readMethodMapping(method) ?: return null
        val cls = PsiTreeUtil.getParentOfType(method, PsiClass::class.java) ?: return null
        val classFqn = cls.qualifiedName ?: return null
        val classMapping = readClassMapping(cls)
        return HandlerMethod(
            className = classFqn,
            methodName = method.name,
            httpMethod = mapping.httpMethod,
            urlTemplate = joinPaths(classMapping?.paths, mapping.paths),
        )
    }

    private fun readMethodMapping(method: PsiMethod): Mapping? {
        if (method is PsiCompiledElement) return null
        val annotations = collectAnnotations(method)
        for ((fqn, implicitMethod) in METHOD_ANNOTATIONS) {
            val ann = findAnnotation(annotations, fqn) ?: continue
            return Mapping(
                httpMethod = implicitMethod ?: readRequestMethodAttribute(ann),
                paths = readPaths(ann),
            )
        }
        return null
    }

    private fun readClassMapping(cls: PsiClass): Mapping? {
        val annotations = collectAnnotations(cls)
        val ann = findAnnotation(annotations, REQUEST_MAPPING_FQN) ?: return null
        return Mapping(
            httpMethod = readRequestMethodAttribute(ann),
            paths = readPaths(ann),
        )
    }

    private fun findAnnotation(
        candidates: List<PsiAnnotation>,
        fqn: String,
    ): PsiAnnotation? {
        val shortName = fqn.substringAfterLast('.')
        val byShort = candidates.firstOrNull { ann ->
            val name = ann.qualifiedName ?: ann.nameReferenceElement?.text
            name?.substringAfterLast('.') == shortName
        } ?: return null
        return if (resolveFqn(byShort, setOf(fqn)) != null) byShort else null
    }

    private fun collectAnnotations(owner: PsiModifierListOwner): List<PsiAnnotation> {
        if (owner is PsiCompiledElement) return emptyList()
        val list = owner.modifierList ?: return emptyList()
        return list.annotations.toList()
    }

    private fun resolveFqn(ann: PsiAnnotation, candidates: Set<String>): String? {
        // Trust PSI's resolved qualifiedName — it is already the FQN of the resolved
        // reference, and JavaPsiFacade.findClass can return null when project indexes
        // are still being built, which causes false negatives.
        val qfn = ann.qualifiedName
        if (qfn != null) return candidates.firstOrNull { it == qfn }

        // Fallback for unresolved references (e.g. missing imports).
        val shortName = ann.nameReferenceElement?.text?.substringAfterLast('.') ?: return null
        val match = candidates.firstOrNull { it.substringAfterLast('.') == shortName } ?: return null
        val project = ann.project
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val found = facade.findClass(match, scope)
        if (found == null) {
            log.warn("[PCS] resolveFqn: Spring class '$match' NOT on classpath (project=${project.name})")
        }
        return if (found != null) match else null
    }

    private fun readPaths(ann: PsiAnnotation): List<String> {
        val pathAttr = ann.findAttributeValue("path")
        val valueAttr = ann.findAttributeValue("value")
        val raw = pathAttr ?: valueAttr
        return stringListFrom(raw).nullize() ?: emptyList()
    }

    private fun readRequestMethodAttribute(ann: PsiAnnotation): String? {
        val attr = ann.findAttributeValue("method") ?: return null
        // Find the first `RequestMethod.X` reference and return the trailing identifier.
        val ref = PsiTreeUtil.findChildOfType(attr, PsiReferenceExpression::class.java) ?: return null
        return ref.text.substringAfterLast('.')
    }

    private fun stringListFrom(value: PsiAnnotationMemberValue?): List<String> {
        if (value == null) return emptyList()
        return when (value) {
            is PsiLiteralExpression -> {
                val v = value.value
                if (v is String) listOf(v) else emptyList()
            }
            is PsiArrayInitializerMemberValue -> value.initializers.flatMap { stringListFrom(it) }
            is PsiReferenceExpression -> emptyList()
            else -> leafStringLiterals(value)
        }
    }

    private fun leafStringLiterals(element: PsiElement): List<String> {
        val result = mutableListOf<String>()
        element.acceptChildren(object : com.intellij.psi.JavaElementVisitor() {
            override fun visitLiteralExpression(expression: com.intellij.psi.PsiLiteralExpression) {
                val v = expression.value
                if (v is String) result.add(v)
            }
        })
        return result
    }

    fun joinPaths(prefix: List<String>?, method: List<String>): String? {
        if (method.isEmpty()) {
            return prefix?.firstOrNull()?.let { normalizePath(it) }
        }
        val firstMethod = method.first()
        val firstPrefix = prefix?.firstOrNull()
        val combined = if (firstPrefix.isNullOrEmpty()) firstMethod else "$firstPrefix$firstMethod"
        return normalizePath(combined)
    }

    private fun normalizePath(path: String): String {
        val collapsed = path.replace("/{2,}".toRegex(), "/")
        return if (collapsed.isEmpty()) "/" else collapsed
    }

    @Suppress("unused")
    private fun unusedLeaf(): LeafPsiElement? = null
}
