import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Auth } from '../../core/auth';
import { I18n, TranslatePipe } from '../../core/i18n';

@Component({
  imports: [FormsModule, TranslatePipe],
  selector: 'app-login-page',
  styleUrl: './login.css',
  templateUrl: './login.html',
})
export class LoginPage {
  user = '';
  password = '';
  remember = true;
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly i18n = inject(I18n);

  constructor(
    private auth: Auth,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  submit(): void {
    if (!this.user.trim() || !this.password) return;
    this.busy.set(true);
    this.error.set(null);
    this.auth.login(this.user.trim(), this.password, this.remember).subscribe({
      next: () => {
        const target = this.route.snapshot.queryParamMap.get('returnUrl') || '/';
        void this.router.navigateByUrl(/^\/(?:cases\/[a-f0-9-]+|settings|search|audit)?(?:\?[^\\]*)?$/.test(target) ? target : '/');
      },
      error: () => {
        this.busy.set(false);
        this.error.set(this.i18n.t('login.failed'));
      },
    });
  }
}
