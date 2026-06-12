import sbt.{ClasspathDependency, LocalProject, Project}

object ProjectOps {
  implicit class ProjectOpsImpl(val p: Project) extends AnyVal {
    def withOptionalDeps(deps: Seq[Project]): Project =
      deps.foldLeft(p) { (acc, dep) =>
        val ref: ClasspathDependency = ClasspathDependency(LocalProject(dep.id), None)
        acc.dependsOn(ref)
      }
  }
}

