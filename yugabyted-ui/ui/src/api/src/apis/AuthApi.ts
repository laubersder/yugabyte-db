// tslint:disable
/**
 * Yugabyte Cloud
 * YugabyteDB as a Service
 *
 * The version of the OpenAPI document: v1
 * Contact: support@yugabyte.com
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */

// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore
import { useQuery, useInfiniteQuery, useMutation, UseQueryOptions, UseInfiniteQueryOptions, UseMutationOptions } from 'react-query';
import Axios from '../runtime';
import type { AxiosInstance } from 'axios';
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore
import type {
  ApiError,
  AuthTokenListResponse,
  AuthTokenResponse,
  AuthTokenSpec,
  CreateAuthTokenResponse,
  LoginRequest,
  LoginResponse,
  RoleListResponse,
} from '../models';

export interface CreateAuthTokenForQuery {
  AuthTokenSpec?: AuthTokenSpec;
}
export interface DeleteAuthTokenForQuery {
  tokenId: string;
}
export interface GetAuthTokenForQuery {
  tokenId: string;
}
export interface ListAuthTokensForQuery {
  order?: string;
  order_by?: string;
  limit?: number;
  continuation_token?: string;
}
export interface LoginForQuery {
  LoginRequest: LoginRequest;
}
export interface LogoutForQuery {
  logout_all_sessions?: boolean;
}

/**
 * Create a new auth token
 * Create a new auth token
 */


export const createAuthTokenMutate = (
  body: CreateAuthTokenForQuery,
  customAxiosInstance?: AxiosInstance
) => {
  const url = '/public/auth/tokens';
  return Axios<CreateAuthTokenResponse>(
    {
      url,
      method: 'POST',
      data: body.AuthTokenSpec
    },
    customAxiosInstance
  );
};

export const useCreateAuthTokenMutation = <Error = ApiError>(
  options?: {
    mutation?:UseMutationOptions<CreateAuthTokenResponse, Error>,
    customAxiosInstance?: AxiosInstance;
  }
) => {
  const {mutation: mutationOptions, customAxiosInstance} = options ?? {};
  // eslint-disable-next-line
  // @ts-ignore
  return useMutation<CreateAuthTokenResponse, Error, CreateAuthTokenForQuery, unknown>((props) => {
    return  createAuthTokenMutate(props, customAxiosInstance);
  }, mutationOptions);
};


/**
 * Delete auth token
 * Delete auth token
 */


export const deleteAuthTokenMutate = (
  body: DeleteAuthTokenForQuery,
  customAxiosInstance?: AxiosInstance
) => {
  const url = '/public/auth/tokens/{tokenId}'.replace(`{${'tokenId'}}`, encodeURIComponent(String(body.tokenId)));
  // eslint-disable-next-line
  // @ts-ignore
  delete body.tokenId;
  return Axios<unknown>(
    {
      url,
      method: 'DELETE',
    },
    customAxiosInstance
  );
};

export const useDeleteAuthTokenMutation = <Error = ApiError>(
  options?: {
    mutation?:UseMutationOptions<unknown, Error>,
    customAxiosInstance?: AxiosInstance;
  }
) => {
  const {mutation: mutationOptions, customAxiosInstance} = options ?? {};
  // eslint-disable-next-line
  // @ts-ignore
  return useMutation<unknown, Error, DeleteAuthTokenForQuery, unknown>((props) => {
    return  deleteAuthTokenMutate(props, customAxiosInstance);
  }, mutationOptions);
};


/**
 * Get auth token
 * Get auth token
 */

export const getAuthTokenAxiosRequest = (
  requestParameters: GetAuthTokenForQuery,
  customAxiosInstance?: AxiosInstance
) => {
  return Axios<AuthTokenResponse>(
    {
      url: '/public/auth/tokens/{tokenId}'.replace(`{${'tokenId'}}`, encodeURIComponent(String(requestParameters.tokenId))),
      method: 'GET',
      params: {
      }
    },
    customAxiosInstance
  );
};

export const getAuthTokenQueryKey = (
  requestParametersQuery: GetAuthTokenForQuery,
  pageParam = -1,
  version = 1,
) => [
  `/v${version}/public/auth/tokens/{tokenId}`,
  pageParam,
  ...(requestParametersQuery ? [requestParametersQuery] : [])
];


export const useGetAuthTokenInfiniteQuery = <T = AuthTokenResponse, Error = ApiError>(
  params: GetAuthTokenForQuery,
  options?: {
    query?: UseInfiniteQueryOptions<AuthTokenResponse, Error, T>;
    customAxiosInstance?: AxiosInstance;
  },
  pageParam = -1,
  version = 1,
) => {
  const queryKey = getAuthTokenQueryKey(params, pageParam, version);
  const { query: queryOptions, customAxiosInstance } = options ?? {};

  const query = useInfiniteQuery<AuthTokenResponse, Error, T>(
    queryKey,
    () => getAuthTokenAxiosRequest(params, customAxiosInstance),
    queryOptions
  );

  return {
    queryKey,
    ...query
  };
};

export const useGetAuthTokenQuery = <T = AuthTokenResponse, Error = ApiError>(
  params: GetAuthTokenForQuery,
  options?: {
    query?: UseQueryOptions<AuthTokenResponse, Error, T>;
    customAxiosInstance?: AxiosInstance;
  },
  version = 1,
) => {
  const queryKey = getAuthTokenQueryKey(params,  version);
  const { query: queryOptions, customAxiosInstance } = options ?? {};

  const query = useQuery<AuthTokenResponse, Error, T>(
    queryKey,
    () => getAuthTokenAxiosRequest(params, customAxiosInstance),
    queryOptions
  );

  return {
    queryKey,
    ...query
  };
};



/**
 * List auth tokens
 * List auth tokens
 */

export const listAuthTokensAxiosRequest = (
  requestParameters: ListAuthTokensForQuery,
  customAxiosInstance?: AxiosInstance
) => {
  return Axios<AuthTokenListResponse>(
    {
      url: '/public/auth/tokens',
      method: 'GET',
      params: {
        order: requestParameters['order'],
        order_by: requestParameters['order_by'],
        limit: requestParameters['limit'],
        continuation_token: requestParameters['continuation_token'],
      }
    },
    customAxiosInstance
  );
};

export const listAuthTokensQueryKey = (
  requestParametersQuery: ListAuthTokensForQuery,
  pageParam = -1,
  version = 1,
) => [
  `/v${version}/public/auth/tokens`,
  pageParam,
  ...(requestParametersQuery ? [requestParametersQuery] : [])
];


export const useListAuthTokensInfiniteQuery = <T = AuthTokenListResponse, Error = ApiError>(
  params: ListAuthTokensForQuery,
  options?: {
    query?: UseInfiniteQueryOptions<AuthTokenListResponse, Error, T>;
    customAxiosInstance?: AxiosInstance;
  },
  pageParam = -1,
  version = 1,
) => {
  const queryKey = listAuthTokensQueryKey(params, pageParam, version);
  const { query: queryOptions, customAxiosInstance } = options ?? {};

  const query = useInfiniteQuery<AuthTokenListResponse, Error, T>(
    queryKey,
    () => listAuthTokensAxiosRequest(params, customAxiosInstance),
    queryOptions
  );

  return {
    queryKey,
    ...query
  };
};

export const useListAuthTokensQuery = <T = AuthTokenListResponse, Error = ApiError>(
  params: ListAuthTokensForQuery,
  options?: {
    query?: UseQueryOptions<AuthTokenListResponse, Error, T>;
    customAxiosInstance?: AxiosInstance;
  },
  version = 1,
) => {
  const queryKey = listAuthTokensQueryKey(params,  version);
  const { query: queryOptions, customAxiosInstance } = options ?? {};

  const query = useQuery<AuthTokenListResponse, Error, T>(
    queryKey,
    () => listAuthTokensAxiosRequest(params, customAxiosInstance),
    queryOptions
  );

  return {
    queryKey,
    ...query
  };
};



/**
 * List of system defined RBAC roles
 * List system defined RBAC roles
 */

export const listRolesAxiosRequest = (
  customAxiosInstance?: AxiosInstance
) => {
  return Axios<RoleListResponse>(
    {
      url: '/public/auth/roles',
      method: 'GET',
      params: {
      }
    },
    customAxiosInstance
  );
};

export const listRolesQueryKey = (
  pageParam = -1,
  version = 1,
) => [
  `/v${version}/public/auth/roles`,
  pageParam,
];


export const useListRolesInfiniteQuery = <T = RoleListResponse, Error = ApiError>(
  options?: {
    query?: UseInfiniteQueryOptions<RoleListResponse, Error, T>;
    customAxiosInstance?: AxiosInstance;
  },
  pageParam = -1,
  version = 1,
) => {
  const queryKey = listRolesQueryKey(pageParam, version);
  const { query: queryOptions, customAxiosInstance } = options ?? {};

  const query = useInfiniteQuery<RoleListResponse, Error, T>(
    queryKey,
    () => listRolesAxiosRequest(customAxiosInstance),
    queryOptions
  );

  return {
    queryKey,
    ...query
  };
};

export const useListRolesQuery = <T = RoleListResponse, Error = ApiError>(
  options?: {
    query?: UseQueryOptions<RoleListResponse, Error, T>;
    customAxiosInstance?: AxiosInstance;
  },
  version = 1,
) => {
  const queryKey = listRolesQueryKey(version);
  const { query: queryOptions, customAxiosInstance } = options ?? {};

  const query = useQuery<RoleListResponse, Error, T>(
    queryKey,
    () => listRolesAxiosRequest(customAxiosInstance),
    queryOptions
  );

  return {
    queryKey,
    ...query
  };
};



/**
 * Login a user
 * Login a user
 */


export const loginMutate = (
  body: LoginForQuery,
  customAxiosInstance?: AxiosInstance
) => {
  const url = '/public/auth/login';
  return Axios<LoginResponse>(
    {
      url,
      method: 'POST',
      data: body.LoginRequest
    },
    customAxiosInstance
  );
};

export const useLoginMutation = <Error = ApiError>(
  options?: {
    mutation?:UseMutationOptions<LoginResponse, Error>,
    customAxiosInstance?: AxiosInstance;
  }
) => {
  const {mutation: mutationOptions, customAxiosInstance} = options ?? {};
  // eslint-disable-next-line
  // @ts-ignore
  return useMutation<LoginResponse, Error, LoginForQuery, unknown>((props) => {
    return  loginMutate(props, customAxiosInstance);
  }, mutationOptions);
};


/**
 * Logout a user
 * Logout a user
 */


export const logoutMutate = (
  customAxiosInstance?: AxiosInstance
) => {
  const url = '/public/auth/logout';
  return Axios<unknown>(
    {
      url,
      method: 'POST',
    },
    customAxiosInstance
  );
};

export const useLogoutMutation = <Error = ApiError>(
  options?: {
    mutation?:UseMutationOptions<unknown, Error>,
    customAxiosInstance?: AxiosInstance;
  }
) => {
  const {mutation: mutationOptions, customAxiosInstance} = options ?? {};
  // eslint-disable-next-line
  // @ts-ignore
  return useMutation<unknown, Error, void, unknown>(() => {
    return  logoutMutate(customAxiosInstance);
  }, mutationOptions);
};





