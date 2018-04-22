package scalaz.ioeffect

import cats.effect
import cats.effect.ConcurrentEffect
import cats.syntax.all._

import scala.util.control.NonFatal
import scalaz._

package object catscompat extends RTS {

  private def toCatsFiber[E, A](f: Fiber[E, A]): effect.Fiber[IO[E, ?], A] =
    effect.Fiber[IO[E, ?], A](f.join, f.interrupt(new Exception))

  implicit val catsConcurrentEffect = new ConcurrentEffect[Task] {
    def runCancelable[A](
        fa: Task[A]
    )(cb: Either[Throwable, A] => effect.IO[Unit])
      : effect.IO[effect.IO[Unit]] = {
      val f = fa
        .attempt[Throwable]
        .flatMap(e => IO.syncThrowable(cb(e.toEither).unsafeRunAsync(_ => ())))
        .fork[Throwable]

      effect.IO {
        val fiber = unsafePerformIO(f)
        effect.IO {
          unsafePerformIO(fiber.interrupt(new Exception))
        }
      }
    }

    def cancelable[A](
        k: (Either[Throwable, A] => Unit) => effect.IO[Unit]
    ): Task[A] = {
      val cb = k.compose[ExitResult[Throwable, A] => Unit] {
        _.compose[Either[Throwable, A]] {
          case Left(r)  => ExitResult.Failed(r)
          case Right(r) => ExitResult.Completed(r)
        }
      }

      IO.asyncIO(cb.andThen(io => IO.syncThrowable(io.unsafeRunAsync(_ => ())))) //Not sure if unsafeRunSync
    }

    def uncancelable[A](fa: Task[A]): Task[A] = fa.uninterruptibly

    def onCancelRaiseError[A](fa: Task[A], e: Throwable): Task[A] =
      fa.catchAll(_ => IO.fail(e))

    def start[A](fa: Task[A]): Task[effect.Fiber[Task, A]] =
      fa.fork.map(toCatsFiber)

    def racePair[A, B](
        fa: Task[A],
        fb: Task[B]
    ): Task[Either[(A, effect.Fiber[Task, B]), (effect.Fiber[Task, A], B)]] =
      fa.raceWith(fb) {
        case \/-((res, fib)) => IO.now(Right((toCatsFiber(fib), res)))
        case -\/((res, fib)) => IO.now(Left((res, toCatsFiber(fib))))
      }

    def runAsync[A](
        fa: Task[A]
    )(cb: Either[Throwable, A] => effect.IO[Unit]): effect.IO[Unit] = {
      effect.IO {
        unsafePerformIO(
          fa.attempt[Throwable]
            .map(r => cb(r.toEither).unsafeRunAsync(_ => ()))
            .fork[Throwable]
        )
      }
    }

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): Task[A] = {
      val kk = k.compose[ExitResult[Throwable, A] => Unit] {
        _.compose[Either[Throwable, A]] {
          case Left(t)  => ExitResult.Failed(t)
          case Right(r) => ExitResult.Completed(r)
        }
      }

      IO.async(kk)
    }

    def suspend[A](thunk: => Task[A]): Task[A] = IO.suspend(
      try {
        thunk
      } catch {
        case NonFatal(e) => IO.fail(e)
      }
    )

    def raiseError[A](e: Throwable): Task[A] = IO.fail(e)

    def handleErrorWith[A](fa: Task[A])(f: Throwable => Task[A]): Task[A] =
      fa.catchAll(f)

    def pure[A](x: A): Task[A] = IO.now(x)

    def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] = fa.flatMap(f)

    //LOL monad "law"
    def tailRecM[A, B](a: A)(f: A => Task[Either[A, B]]): Task[B] =
      f(a).flatMap {
        case Left(l)  => tailRecM(l)(f)
        case Right(r) => IO.now(r)
      }
  }

}
