package gitbucket.core.controller

import java.io.File

import gitbucket.core.service.{AccountService, ActivityService, ReleaseService, RepositoryService}
import gitbucket.core.util.{FileUtil, ReadableUsersAuthenticator, ReferrerAuthenticator, WritableUsersAuthenticator}
import gitbucket.core.util.Directory._
import gitbucket.core.util.Implicits._
import gitbucket.core.util.SyntaxSugars._
import gitbucket.core.view.Markdown
import org.scalatra.forms._
import gitbucket.core.releases.html
import org.apache.commons.io.FileUtils

class ReleaseController extends ReleaseControllerBase
  with RepositoryService
  with AccountService
  with ReleaseService
  with ActivityService
  with ReadableUsersAuthenticator
  with ReferrerAuthenticator
  with WritableUsersAuthenticator

trait ReleaseControllerBase extends ControllerBase {
  self: RepositoryService
    with AccountService
    with ReleaseService
    with ReadableUsersAuthenticator
    with ReferrerAuthenticator
    with WritableUsersAuthenticator
    with ActivityService =>

  case class ReleaseCreateForm(
    name: String,
    content: Option[String],
    isPrerelease: Boolean
  )

  val releaseCreateForm = mapping(
    "name"            -> trim(text(required)),
    "content"         -> trim(optional(text())),
    "isprerelease"   -> boolean()
  )(ReleaseCreateForm.apply)

  val releaseTitleEditForm = mapping(
    "title" -> trim(label("Title", text(required)))
  )(x => x)

  val releaseEditForm = mapping(
    "content" -> trim(optional(text()))
  )(x => x)

  get("/:owner/:repository/releases")(referrersOnly {repository =>
    html.list(
      repository,
      getReleases(repository.owner, repository.name),
      getReleaseAssetsMap(repository.owner, repository.name),
      hasDeveloperRole(repository.owner, repository.name, context.loginAccount))
  })

  get("/:owner/:repository/releases/:id")(referrersOnly {repository =>
    val id = params("id")
    getRelease(repository.owner, repository.name, id).map{ release =>
      html.release(release, getReleaseAssets(repository.owner, repository.name, id), hasDeveloperRole(repository.owner, repository.name, context.loginAccount), repository)
    }.getOrElse(NotFound())
  })

  get("/:owner/:repository/releases/:id/assets/:fileId")(referrersOnly {repository =>
    val releaseId = params("id")
    val fileId = params("fileId")
    getRelease(repository.owner, repository.name, releaseId).flatMap{ release =>
      getReleaseAsset(repository.owner, repository.name, releaseId, fileId).flatMap{ asset =>
        response.setHeader("Content-Disposition", s"attachment; filename=${asset.label}")
        Some(RawData(FileUtil.getMimeType(asset.label), new File(getReleaseFilesDir(repository.owner, repository.name) + s"/${release.releaseId}", fileId)))
      }
    }.getOrElse(NotFound())
  })

  ajaxPost("/:owner/:repository/releases/:id/assets/delete/:fileId")(writableUsersOnly { repository =>
    val releaseId = params("id")
    val fileId = params("fileId")
    for(
      release <- getRelease(repository.owner, repository.name, releaseId.toInt);
      asset <- getReleaseAsset(repository.owner, repository.name, releaseId, fileId)
    ){
      FileUtils.forceDelete(new File(getReleaseFilesDir(repository.owner, repository.name) + s"/${release.releaseId}", fileId))
    }
    deleteReleaseAsset(repository.owner, repository.name, params("id").toInt, params("fileId"))
    org.json4s.jackson.Serialization.write(Map("message" -> "ok"))
  })


  get("/:owner/:repository/releases/:tag/create")(writableUsersOnly {repository =>
    val tag = params("tag")
    defining(repository.owner, repository.name){ case (owner, name) =>
      html.create(repository, tag)
    }
  })

  post("/:owner/:repository/releases/:tag/create", releaseCreateForm)(writableUsersOnly { (form, repository) =>
    val tag = params("tag")
    val release = createRelease(repository, form.name, form.content, tag, false, form.isPrerelease, context.loginAccount.get)
    recordReleaseActivity(repository.owner, repository.name, context.loginAccount.get.userName, release.releaseId, release.name)

    redirect(s"/${release.userName}/${release.repositoryName}/releases/${release.releaseId}")
  })

  ajaxPost("/:owner/:repository/releases/delete/:id")(writableUsersOnly { repository =>
    for(
      release <- getRelease(repository.owner, repository.name, params("id"))
    ){
      FileUtils.deleteDirectory(new File(getReleaseFilesDir(repository.owner, repository.name) + s"/${release.releaseId}"))
    }
    deleteRelease(repository.owner, repository.name, params("id"))
    org.json4s.jackson.Serialization.write(Map("message" -> "ok"))
  })

  ajaxPost("/:owner/:repository/releases/edit_title/:id", releaseTitleEditForm)(writableUsersOnly { (title, repository) =>
    defining(repository.owner, repository.name) { case (owner, name) =>
      getRelease(owner, name, params("id")).map { release =>
        updateRelease(owner, name, release.releaseId, title, release.content)
        redirect(s"/${owner}/${name}/releases/_data/${release.releaseId}")
      } getOrElse NotFound()
    }
  })

  ajaxPost("/:owner/:repository/releases/edit/:id", releaseEditForm)(writableUsersOnly { (content, repository) =>
    defining(repository.owner, repository.name){ case (owner, name) =>
      getRelease(owner, name, params("id")).map { release =>
        updateRelease(owner, name, release.releaseId, release.name, content)
        redirect(s"/${owner}/${name}/releases/_data/${release.releaseId}")
      } getOrElse NotFound()
    }
  })

  ajaxGet("/:owner/:repository/releases/_data/:id")(writableUsersOnly { repository =>
    getRelease(repository.owner, repository.name, params("id")) map { x =>
      params.get("dataType") collect {
        case t if t == "html" => html.editrelease(x.content, x.releaseId, repository)
      } getOrElse {
        contentType = formats("json")
        org.json4s.jackson.Serialization.write(
          Map(
            "title"   -> x.name,
            "content" -> Markdown.toHtml(
              markdown = x.content getOrElse "No description given.",
              repository = repository,
              enableWikiLink = false,
              enableRefsLink = true,
              enableAnchor = true,
              enableLineBreaks = true,
              enableTaskList = true,
              hasWritePermission = true
            )
          )
        )
      }
    } getOrElse NotFound()
  })
}
