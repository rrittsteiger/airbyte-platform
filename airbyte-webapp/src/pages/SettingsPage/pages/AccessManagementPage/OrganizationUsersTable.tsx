import { createColumnHelper } from "@tanstack/react-table";
import { useMemo } from "react";
import { FormattedMessage } from "react-intl";

import { Table } from "components/ui/Table";

import { OrganizationUserRead } from "core/api/types/AirbyteClient";
import { useCurrentUser } from "core/services/auth";
import { RbacRoleHierarchy, partitionPermissionType } from "core/utils/rbac/rbacPermissionsQuery";

import { UserCell } from "./components/UserCell";
import { RoleManagementMenu } from "./next/RoleManagementMenu";

export const OrganizationUsersTable: React.FC<{
  users: OrganizationUserRead[];
}> = ({ users }) => {
  const { userId: currentUserId } = useCurrentUser();
  const columnHelper = createColumnHelper<OrganizationUserRead>();

  const columns = useMemo(
    () => [
      columnHelper.accessor("name", {
        header: () => <FormattedMessage id="settings.accessManagement.table.column.member" />,
        cell: (props) => {
          return (
            <UserCell
              name={props.row.original.name}
              email={props.row.original.email}
              isCurrentUser={props.row.original.userId === currentUserId}
              userId={props.row.original.userId}
            />
          );
        },
        sortingFn: "alphanumeric",
        meta: { responsive: true },
      }),
      columnHelper.accessor("permissionType", {
        id: "permissionType",
        header: () => (
          <>
            <FormattedMessage id="resource.organization" />{" "}
            <FormattedMessage id="settings.accessManagement.table.column.role" />
          </>
        ),
        meta: { responsive: true },
        cell: (props) => {
          const user = {
            name: props.row.original.name ?? "",
            userId: props.row.original.userId,
            email: props.row.original.email,
            organizationPermission: {
              permissionType: props.row.original.permissionType,
              organizationId: props.row.original.organizationId,
              permissionId: props.row.original.permissionId,
              userId: props.row.original.userId,
            },
          };

          return <RoleManagementMenu user={user} resourceType="organization" />;
        },
        sortingFn: (a, b, order) => {
          const aRole = partitionPermissionType(a.original.permissionType)[1];
          const bRole = partitionPermissionType(b.original.permissionType)[1];

          const aValue = RbacRoleHierarchy.indexOf(aRole);
          const bValue = RbacRoleHierarchy.indexOf(bRole);

          if (order === "asc") {
            return aValue - bValue;
          }
          return bValue - aValue;
        },
      }),
    ],
    [columnHelper, currentUserId]
  );

  return <Table data={users} columns={columns} initialSortBy={[{ id: "name", desc: false }]} />;
};
