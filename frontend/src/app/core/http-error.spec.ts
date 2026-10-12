import { httpErrorMessage } from './http-error';

describe('httpErrorMessage', () => {
  it('reads Spring problem detail instead of [object Object]', () => {
    expect(
      httpErrorMessage({ error: { status: 400, detail: 'new password must be at least 8 characters' } }),
    ).toBe('new password must be at least 8 characters');
  });

  it('keeps a plain string body', () => {
    expect(httpErrorMessage({ error: 'old password is wrong' })).toBe('old password is wrong');
  });
});
