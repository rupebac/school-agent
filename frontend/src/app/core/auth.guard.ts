import { inject } from '@angular/core';
import { Router, UrlTree } from '@angular/router';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { Auth } from './auth';

export const authGuard = (_route: unknown, state: { url: string }): Observable<boolean | UrlTree> => {
  const auth = inject(Auth);
  const router = inject(Router);
  if (auth.me()) return of(true);
  return auth.restore().pipe(
    map(() => true),
    catchError(() => of(router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } }))),
  );
};
