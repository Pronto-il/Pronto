import { PageHeader } from '../../shared/components';
import { RoleChooser } from './RoleChooser';

export default function RegisterChoicePage() {
  return (
    <div className="focused-page">
      <PageHeader title="הרשמה ל-Pronto" description="איך תרצו להצטרף?" />
      <RoleChooser />
    </div>
  );
}
